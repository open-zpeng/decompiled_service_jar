package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;
/* loaded from: classes.dex */
public class channelData {
    public Policy[] policy;
    public double version;

    public double getVersion() {
        return this.version;
    }

    public void setVersion(double version) {
        this.version = version;
    }

    public Policy[] getPolicy() {
        return this.policy;
    }

    public void setPolicy(Policy[] policy) {
        this.policy = policy;
    }

    /* loaded from: classes.dex */
    public class Policy {
        public int screenId;
        public ScreenPolicy[] screenPolicy;

        public Policy() {
        }

        public int getScreenId() {
            return this.screenId;
        }

        public void setScreenId(int screenId) {
            this.screenId = screenId;
        }

        public ScreenPolicy[] getScreenPolicy() {
            return this.screenPolicy;
        }

        public void setScreenPolicy(ScreenPolicy[] screenPolicy) {
            this.screenPolicy = screenPolicy;
        }
    }

    /* loaded from: classes.dex */
    public class ScreenPolicy {
        public String modeName;
        public String streamType;
        public TypePolicy[] typePolicy;

        public ScreenPolicy() {
        }

        public String getStreamtype() {
            return this.streamType;
        }

        public void setStreamtype(String streamType) {
            this.streamType = streamType;
        }

        public String getModeName() {
            return this.modeName;
        }

        public void setModeName(String modeName) {
            this.modeName = modeName;
        }

        public TypePolicy[] getTypePolicy() {
            return this.typePolicy;
        }

        public void setTypePolicy(TypePolicy[] typePolicy) {
            this.typePolicy = typePolicy;
        }
    }

    /* loaded from: classes.dex */
    public class TypePolicy {
        public int channel;
        public int index;
        public int mode;
        public int priority;
        public String type;

        public TypePolicy() {
        }

        public int getIndex() {
            return this.index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public int getMode() {
            return this.mode;
        }

        public void setMode(int mode) {
            this.mode = mode;
        }

        public String getType() {
            return this.type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getPriority() {
            return this.priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public int getChannel() {
            return this.channel;
        }

        public void setChannel(int channel) {
            this.channel = channel;
        }
    }
}
