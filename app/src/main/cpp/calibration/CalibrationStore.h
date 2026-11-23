#pragma once
#include <opencv2/core.hpp>
#include <algorithm>

namespace va {
struct CalibrationStore {
    cv::Point2f centerNormalized{0.56f, 0.73f};
    float radiusNormalized{0.055f};

    void set(float x, float y, float radius) {
        centerNormalized.x = std::clamp(x, 0.0f, 1.0f);
        centerNormalized.y = std::clamp(y, 0.0f, 1.0f);
        radiusNormalized = std::clamp(radius, 0.01f, 0.25f);
    }

    cv::Point2f center(const cv::Size& frame) const {
        return {centerNormalized.x * frame.width,
                centerNormalized.y * frame.height};
    }

    float radius(const cv::Size& frame) const {
        return radiusNormalized * std::min(frame.width, frame.height);
    }
};
}
