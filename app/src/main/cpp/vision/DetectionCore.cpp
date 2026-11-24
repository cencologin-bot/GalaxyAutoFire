#include "DetectionCore.h"
#include <algorithm>
#include <cmath>

namespace va {

namespace {
constexpr float kMinTplScale = 0.17f;   // relative to the original template
constexpr float kMaxTplScale = 0.62f;
constexpr float kScaleStep   = 1.06f;
}

bool DetectionCore::loadTemplate(const cv::Mat& bgr) {
    if (bgr.empty()) return false;
    if (bgr.channels() == 1) tmplGray_ = bgr.clone();
    else cv::cvtColor(bgr, tmplGray_, bgr.channels() == 4 ? cv::COLOR_BGRA2GRAY : cv::COLOR_BGR2GRAY);
    buildPyramid();
    lockedScale_ = -1;
    return !pyramid_.empty();
}

void DetectionCore::buildPyramid() {
    scales_.clear();
    pyramid_.clear();
    for (float s = kMinTplScale; s <= kMaxTplScale; s *= kScaleStep) {
        cv::Mat t;
        cv::resize(tmplGray_, t, cv::Size(), s, s, cv::INTER_AREA);
        if (t.cols < 12 || t.rows < 12) continue;
        scales_.push_back(s);
        pyramid_.push_back(t);
    }
}

void DetectionCore::calibrateDestination(const cv::Point2f& c, float r) {
    destNorm_.x = std::clamp(c.x, 0.f, 1.f);
    destNorm_.y = std::clamp(c.y, 0.f, 1.f);
    destRadNorm_ = std::clamp(r, 0.005f, 0.25f);
    arrivalCount_ = 0;
    arrived_ = false;
}

void DetectionCore::setSearchRegion(float x, float y, float w, float h) {
    x = std::clamp(x, 0.f, 1.f); y = std::clamp(y, 0.f, 1.f);
    w = std::clamp(w, 0.05f, 1.f - x); h = std::clamp(h, 0.05f, 1.f - y);
    searchNorm_ = cv::Rect2f(x, y, w, h);
}

void DetectionCore::setLanternRegion(float x, float y, float w, float h) {
    x = std::clamp(x, 0.f, 1.f); y = std::clamp(y, 0.f, 1.f);
    w = std::clamp(w, 0.02f, 1.f - x); h = std::clamp(h, 0.02f, 1.f - y);
    lanternNorm_ = cv::Rect2f(x, y, w, h);
    lanternCache_ = LanternInfo();
}

void DetectionCore::setCalibrating(bool on) { calibrating_ = on; }

void DetectionCore::reset() {
    kfInit_ = false;
    misses_ = 0; arrivalCount_ = 0; frameIdx_ = 0; sinceFull_ = 0;
    arrived_ = false;
    status_ = Status::SEARCHING;
    hist_.clear();
    lastTs_ = 0;
    lanternCache_ = LanternInfo();
}

bool DetectionCore::colorGate(const cv::Mat& hsv, const cv::Point2f& c, float diameter) const {
    // The silver star is nearly colourless; gold/orange stars, gifts and chests are saturated.
    const int r = std::max(3, int(diameter * 0.20f));
    cv::Rect box(int(c.x) - r, int(c.y) - r, 2 * r + 1, 2 * r + 1);
    box &= cv::Rect(0, 0, hsv.cols, hsv.rows);
    if (box.width < 3 || box.height < 3) return false;
    cv::Mat mask(box.size(), CV_8U, cv::Scalar(0));
    cv::circle(mask, cv::Point(int(c.x) - box.x, int(c.y) - box.y), r, cv::Scalar(255), cv::FILLED);
    cv::Mat sat;
    cv::extractChannel(hsv(box), sat, 1);
    return cv::mean(sat, mask)[0] <= p_.maxSaturation;
}

DetectionCore::Match DetectionCore::matchInRect(const cv::Mat& gray, const cv::Mat& hsv,
                                                const cv::Rect& roiIn, int sBegin, int sEnd,
                                                int stride, double threshold) {
    Match best;
    const cv::Rect roi = roiIn & cv::Rect(0, 0, gray.cols, gray.rows);
    if (roi.empty() || pyramid_.empty()) return best;
    sBegin = std::max(0, sBegin);
    sEnd = std::min<int>(sEnd, int(pyramid_.size()) - 1);
    const cv::Mat view = gray(roi);

    for (int i = sBegin; i <= sEnd; i += std::max(1, stride)) {
        const cv::Mat& t = pyramid_[i];
        if (t.cols >= view.cols || t.rows >= view.rows) continue;
        cv::Mat scores;
        cv::matchTemplate(view, t, scores, cv::TM_CCOEFF_NORMED);
        cv::patchNaNs(scores, 0.0);

        for (int k = 0; k < 3; ++k) {                      // top-3 peaks per scale
            double mx; cv::Point loc;
            cv::minMaxLoc(scores, nullptr, &mx, nullptr, &loc);
            if (mx < threshold) break;
            const cv::Point2f c(roi.x + loc.x + t.cols * 0.5f, roi.y + loc.y + t.rows * 0.5f);
            if (colorGate(hsv, c, float(t.cols)) && mx > best.score) {
                best.ok = true; best.center = c; best.size = float(t.cols);
                best.score = mx; best.scaleIdx = i;
            }
            cv::rectangle(scores,
                cv::Rect(loc.x - t.cols / 2, loc.y - t.rows / 2, t.cols, t.rows),
                cv::Scalar(-1.0), cv::FILLED);              // non-maximum suppression
        }
    }
    return best;
}

LanternInfo DetectionCore::findLantern(const cv::Mat& hsv, const cv::Rect& region, float k) const {
    LanternInfo out;
    const cv::Rect roi = region & cv::Rect(0, 0, hsv.cols, hsv.rows);
    if (roi.empty()) return out;
    cv::Mat view = hsv(roi), green, red1, red2;
    cv::inRange(view, cv::Scalar(40, 110, 140), cv::Scalar(88, 255, 255), green);
    cv::inRange(view, cv::Scalar(0, 120, 120),  cv::Scalar(18, 255, 255), red1);   // real game: red lamp is orange-red
    cv::inRange(view, cv::Scalar(172, 150, 200), cv::Scalar(180, 255, 255), red2);
    cv::Mat redMask = red1 | red2, all = green | redMask;
    cv::morphologyEx(all, all, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_RECT, {3, 3}));

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(all, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    double bestArea = 0; int bestIdx = -1;
    for (size_t i = 0; i < contours.size(); ++i) {
        const double a = cv::contourArea(contours[i]);
        if (a > bestArea) { bestArea = a; bestIdx = int(i); }
    }
    if (bestIdx < 0 || bestArea < 60.0) return out;
    const cv::Rect b = cv::boundingRect(contours[bestIdx]);
    out.found = true;
    out.center = cv::Point2f((roi.x + b.x + b.width * 0.5f) / k, (roi.y + b.y + b.height * 0.5f) / k);
    cv::Mat blobMask(all.size(), CV_8U, cv::Scalar(0));
    cv::drawContours(blobMask, contours, bestIdx, cv::Scalar(255), cv::FILLED);
    out.colorCode = cv::countNonZero(green & blobMask) >= cv::countNonZero(redMask & blobMask) ? 1 : 2;
    return out;
}

FrameResult DetectionCore::process(const cv::Mat& frame, double ts) {
    FrameResult r;
    if (frame.empty() || frame.type() != CV_8UC3) return r;
    const int fw = frame.cols, fh = frame.rows;
    r.frameW = fw; r.frameH = fh;
    r.destination = cv::Point2f(destNorm_.x * fw, destNorm_.y * fh);
    r.destinationRadius = destRadNorm_ * std::min(fw, fh);

    // ---- timing ---------------------------------------------------------------
    double dt = (lastTs_ > 0) ? (ts - lastTs_) / 1000.0 : 1.0 / 30.0;
    dt = std::clamp(dt, 0.001, 0.5);
    lastTs_ = ts;
    const float inst = float(1.0 / dt);
    fpsEma_ = fpsEma_ <= 0.f ? inst : fpsEma_ * 0.9f + inst * 0.1f;
    r.fps = fpsEma_;
    ++frameIdx_;

    if (calibrating_) {
        r.status = status_ = Status::CALIBRATING;
        return r;
    }

    // ---- work-resolution frame ----------------------------------------------------
    const float k = std::min(1.0f, float(p_.workWidth) / fw);
    cv::Mat work, gray, hsv;
    if (k < 1.f) cv::resize(frame, work, cv::Size(), k, k, cv::INTER_AREA);
    else work = frame;
    cv::cvtColor(work, gray, cv::COLOR_BGR2GRAY);
    cv::cvtColor(work, hsv, cv::COLOR_BGR2HSV);
    const int ww = work.cols, wh = work.rows;
    const cv::Rect searchRect = cv::Rect(int(searchNorm_.x * ww), int(searchNorm_.y * wh),
                                         int(searchNorm_.width * ww), int(searchNorm_.height * wh))
                                & cv::Rect(0, 0, ww, wh);

    // ---- lantern (fixed reference, refreshed periodically) -----------------------------
    if (!lanternCache_.found || frameIdx_ % 30 == 0) {
        const cv::Rect lr(int(lanternNorm_.x * ww), int(lanternNorm_.y * wh),
                          int(lanternNorm_.width * ww), int(lanternNorm_.height * wh));
        LanternInfo li = findLantern(hsv, lr, k);
        if (li.found) lanternCache_ = li;
    }
    r.lantern = lanternCache_;

    // ---- predict ---------------------------------------------------------------------
    const bool haveTrack = kfInit_ && misses_ < p_.giveUpFrames;
    cv::Point2f pred;
    if (haveTrack) {
        kf_.transitionMatrix.at<float>(0, 2) = float(dt);
        kf_.transitionMatrix.at<float>(1, 3) = float(dt);
        cv::Mat s = kf_.predict();
        pred = cv::Point2f(s.at<float>(0), s.at<float>(1));
    }

    // ---- measure -----------------------------------------------------------------------
    Match m;
    const int nScales = int(pyramid_.size());
    if (haveTrack && lockedScale_ >= 0) {
        // 1) local window around the Kalman prediction (fast path)
        const float sizeW = float(pyramid_[lockedScale_].cols);
        const float speedW = std::hypot(kf_.statePost.at<float>(2), kf_.statePost.at<float>(3)) * k;
        const float margin = std::max(1.3f * sizeW, float(speedW * dt * 2.5) + 0.5f * sizeW)
                             + misses_ * 0.25f * sizeW;
        const cv::Point2f pw = pred * k;
        cv::Rect roi(int(pw.x - sizeW * 0.5f - margin), int(pw.y - sizeW * 0.5f - margin),
                     int(sizeW + 2 * margin), int(sizeW + 2 * margin));
        roi &= searchRect;
        m = matchInRect(gray, hsv, roi, lockedScale_ - 1, lockedScale_ + 1, 1, p_.reacquireThreshold);
        // 2) periodic full-region re-acquisition while we are missing the target
        if (!m.ok && misses_ > 0 && ++sinceFull_ >= p_.fullSearchEvery) {
            sinceFull_ = 0;
            m = matchInRect(gray, hsv, searchRect, lockedScale_ - 2, lockedScale_ + 2, 1, p_.matchThreshold);
        }
    } else if (lockedScale_ >= 0) {
        m = matchInRect(gray, hsv, searchRect, lockedScale_ - 2, lockedScale_ + 2, 1, p_.matchThreshold);
    } else if (frameIdx_ % 2 == 0) {
        m = matchInRect(gray, hsv, searchRect, 0, nScales - 1, 2, p_.matchThreshold); // coarse scale scan
    }

    // ---- correct / coast ---------------------------------------------------------------
    bool measured = false;
    if (m.ok) {
        measured = true;
        const cv::Point2f c = m.center * (1.f / k);
        lockedScale_ = m.scaleIdx;
        lastSize_ = m.size / k;
        if (!kfInit_ || misses_ > p_.lostToLostFrames) {
            kf_ = cv::KalmanFilter(4, 2, 0, CV_32F);
            kf_.transitionMatrix = (cv::Mat_<float>(4, 4) << 1, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1);
            kf_.measurementMatrix = (cv::Mat_<float>(2, 4) << 1, 0, 0, 0, 0, 1, 0, 0);
            cv::setIdentity(kf_.processNoiseCov, cv::Scalar::all(1.f));
            kf_.processNoiseCov.at<float>(2, 2) = 400.f;   // velocity may change quickly
            kf_.processNoiseCov.at<float>(3, 3) = 400.f;
            cv::setIdentity(kf_.measurementNoiseCov, cv::Scalar::all(4.f));
            cv::setIdentity(kf_.errorCovPost, cv::Scalar::all(10.f));
            kf_.statePost = (cv::Mat_<float>(4, 1) << c.x, c.y, 0.f, 0.f);
            kf_.statePre = kf_.statePost.clone();
            kfInit_ = true;
            hist_.clear();
        } else {
            kf_.correct((cv::Mat_<float>(2, 1) << c.x, c.y));
        }
        misses_ = 0;
        sinceFull_ = 0;
    } else {
        ++misses_;
        if (kfInit_) {
            kf_.statePre.copyTo(kf_.statePost);            // coast along prediction
            kf_.errorCovPre.copyTo(kf_.errorCovPost);
        }
        if (kfInit_ && misses_ > p_.giveUpFrames) {
            kfInit_ = false; arrived_ = false; arrivalCount_ = 0; hist_.clear();
        }
    }

    // ---- output ---------------------------------------------------------------------------
    r.measured = measured;
    if (kfInit_) {
        const cv::Point2f c(kf_.statePost.at<float>(0), kf_.statePost.at<float>(1));
        const float half = std::max(8.f, lastSize_) * 0.5f;
        r.target.found = true;
        r.target.center = c;
        r.target.bounds = cv::Rect2f(c.x - half, c.y - half, half * 2, half * 2);
        r.target.confidence = measured ? m.score : 0.0;
        r.velocityPxS = cv::Point2f(kf_.statePost.at<float>(2), kf_.statePost.at<float>(3));
        r.speedPxS = std::hypot(r.velocityPxS.x, r.velocityPxS.y);
        if (measured) {
            hist_.push_back(c);
            while (hist_.size() > p_.historySize) hist_.pop_front();
        }

        const double dist = cv::norm(c - r.destination);
        r.insideDestination = dist <= r.destinationRadius * (arrived_ ? 1.15f : 1.f);
        if (measured) {
            if (r.insideDestination) { if (++arrivalCount_ >= p_.arrivalFrames) arrived_ = true; }
            else { arrivalCount_ = 0; arrived_ = false; }       // re-arm for the next arrival
        }
        if (r.lantern.found && r.speedPxS > 15.f) {
            const cv::Point2f to = r.lantern.center - c;
            r.movingTowardLantern = (to.x * r.velocityPxS.x + to.y * r.velocityPxS.y) > 0;
        }
    }
    r.history.assign(hist_.begin(), hist_.end());

    if (arrived_)                                 status_ = Status::ARRIVED;
    else if (kfInit_ && misses_ < p_.lostToLostFrames) status_ = Status::TRACKING;
    else if (kfInit_)                             status_ = Status::LOST;
    else                                          status_ = Status::SEARCHING;
    r.status = status_;
    return r;
}

} // namespace va
