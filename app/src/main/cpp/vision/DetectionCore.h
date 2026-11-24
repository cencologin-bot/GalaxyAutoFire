#pragma once
#include <opencv2/opencv.hpp>
#include <deque>
#include <vector>

namespace va {

enum class Status { SEARCHING = 0, TRACKING = 1, LOST = 2, ARRIVED = 3, CALIBRATING = 4 };

struct Detection {
    bool found = false;
    cv::Rect2f bounds;      // in original frame coordinates
    cv::Point2f center;     // in original frame coordinates
    double confidence = 0.0;
};

struct LanternInfo {
    bool found = false;
    cv::Point2f center;     // original frame coordinates
    int colorCode = 0;      // 0 unknown, 1 green, 2 red (informative only)
};

struct FrameResult {
    Status status = Status::SEARCHING;
    Detection target;               // measured or predicted (predicted => !measured)
    bool measured = false;
    int frameW = 0, frameH = 0;
    cv::Point2f velocityPxS{0, 0};  // px/s in original frame
    float speedPxS = 0.f;
    float fps = 0.f;
    cv::Point2f destination;        // px
    float destinationRadius = 0.f;  // px
    bool insideDestination = false;
    LanternInfo lantern;
    bool movingTowardLantern = false;
    std::vector<cv::Point2f> history;
};

struct Params {
    double matchThreshold = 0.85;   // NCC needed to accept a measurement
    double reacquireThreshold = 0.80;
    float  maxSaturation = 70.f;    // silver star: low saturation (gold/orange are >140)
    int    workWidth = 480;         // frames are downscaled to this width for matching
    int    arrivalFrames = 3;
    int    lostToLostFrames = 6;    // misses before LOST
    int    giveUpFrames = 90;       // misses before going back to SEARCHING
    int    fullSearchEvery = 12;    // full-frame re-acquire period while not tracking
    size_t historySize = 64;
};

class DetectionCore {
public:
    bool loadTemplate(const cv::Mat& bgr);
    void calibrateDestination(const cv::Point2f& normCenter, float normRadius);
    void setSearchRegion(float x, float y, float w, float h); // normalized, 0..1
    void setLanternRegion(float x, float y, float w, float h); // normalized
    void setCalibrating(bool on);
    void reset();

    // Main entry point. `frame` is BGR (CV_8UC3). timestampMs is a monotonic clock.
    FrameResult process(const cv::Mat& frame, double timestampMs);

private:
    struct Match { bool ok = false; cv::Point2f center; float size = 0; double score = 0; int scaleIdx = -1; };

    Match matchInRect(const cv::Mat& gray, const cv::Mat& hsv, const cv::Rect& roi,
                      int scaleBegin, int scaleEnd, int stride, double threshold);
    bool colorGate(const cv::Mat& hsv, const cv::Point2f& c, float diameter) const;
    LanternInfo findLantern(const cv::Mat& hsv, const cv::Rect& region, float k) const;
    void buildPyramid();

    Params p_;
    cv::Mat tmplGray_;
    std::vector<float> scales_;               // relative to original template
    std::vector<cv::Mat> pyramid_;            // gray templates
    int lockedScale_ = -1;

    cv::Point2f destNorm_{0.35f, 0.776f};
    float destRadNorm_ = 0.07f;
    cv::Rect2f searchNorm_{0.f, 0.58f, 1.f, 0.26f};   // default: game strip
    cv::Rect2f lanternNorm_{0.72f, 0.58f, 0.28f, 0.16f};
    LanternInfo lanternCache_;

    cv::KalmanFilter kf_;
    bool kfInit_ = false;
    double lastTs_ = 0;
    float fpsEma_ = 0.f;
    int misses_ = 0, arrivalCount_ = 0, frameIdx_ = 0, sinceFull_ = 0;
    bool arrived_ = false, calibrating_ = false;
    Status status_ = Status::SEARCHING;
    float lastSize_ = 0.f;
    std::deque<cv::Point2f> hist_;
};

} // namespace va
