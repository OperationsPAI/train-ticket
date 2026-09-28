package com.trainticket.payment.application;

import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.PaymentChannel;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The channel table the router routes with.
 *
 * The table was compiled in. It is read from the service's own settings so a
 * release can move a ceiling, a window or a share without a rebuild, and every
 * field keeps the value this service shipped with when neither the settings
 * file nor the environment names it.
 */
@ConfigurationProperties(prefix = "payment.channel")
public class PaymentChannelSettings {
    private Channel alipay = new Channel("ALIPAY", "ALIPAY", 500_000, 30, true, 45);
    private Channel wechatPay = new Channel("WECHAT_PAY", "WECHAT_PAY", 200_000, 30, true, 40);
    private Channel unionpay = new Channel("UNIONPAY", "UNIONPAY", 1_000_000, 60, true, 15);

    public Channel getAlipay() {
        return alipay;
    }

    public void setAlipay(Channel alipay) {
        this.alipay = alipay;
    }

    public Channel getWechatPay() {
        return wechatPay;
    }

    public void setWechatPay(Channel wechatPay) {
        this.wechatPay = wechatPay;
    }

    public Channel getUnionpay() {
        return unionpay;
    }

    public void setUnionpay(Channel unionpay) {
        this.unionpay = unionpay;
    }

    public ChannelRouter toRouter() {
        return new ChannelRouter(List.of(
            alipay.toChannel(),
            wechatPay.toChannel(),
            unionpay.toChannel()
        ));
    }

    /** One channel's own terms. */
    public static final class Channel {
        private String channelId;
        private String channelType;
        private long maxAmountMinor;
        private int timeoutSeconds;
        private boolean enabled;
        private int weight;

        public Channel() {
            this("ALIPAY", "ALIPAY", 500_000, 30, true, 0);
        }

        Channel(String channelId, String channelType, long maxAmountMinor, int timeoutSeconds, boolean enabled, int weight) {
            this.channelId = channelId;
            this.channelType = channelType;
            this.maxAmountMinor = maxAmountMinor;
            this.timeoutSeconds = timeoutSeconds;
            this.enabled = enabled;
            this.weight = weight;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getChannelType() {
            return channelType;
        }

        public void setChannelType(String channelType) {
            this.channelType = channelType;
        }

        public long getMaxAmountMinor() {
            return maxAmountMinor;
        }

        public void setMaxAmountMinor(long maxAmountMinor) {
            this.maxAmountMinor = maxAmountMinor;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        PaymentChannel toChannel() {
            return new PaymentChannel(channelId, channelType, maxAmountMinor, timeoutSeconds, enabled, weight);
        }
    }
}
