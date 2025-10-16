package com.example.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private Policy authorize = new Policy();
    private Policy capture = new Policy();
    private Policy refund = new Policy();

    public Policy getAuthorize() {
        return authorize;
    }

    public void setAuthorize(Policy authorize) {
        this.authorize = authorize;
    }

    public Policy getCapture() {
        return capture;
    }

    public void setCapture(Policy capture) {
        this.capture = capture;
    }

    public Policy getRefund() {
        return refund;
    }

    public void setRefund(Policy refund) {
        this.refund = refund;
    }

    public static class Policy {
        /**
         * Redis TTL window in seconds.
         */
        private int windowSeconds = 60;

        /**
         * Maximum allowed requests within the window.
         */
        private int capacity = 20;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }
}
