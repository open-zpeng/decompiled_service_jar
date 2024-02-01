package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

/* loaded from: classes2.dex */
public class massageSeatEffect {
    public SeatEffectData[] data;
    public int modeIndex;
    public double version;

    public double getVersion() {
        return this.version;
    }

    public void setVersion(double version) {
        this.version = version;
    }

    public int getModeIndex() {
        return this.modeIndex;
    }

    public void setModeIndex(int modeIndex) {
        this.modeIndex = modeIndex;
    }

    public SeatEffectData[] getSeatEffectData() {
        return this.data;
    }

    public void setSeatEffectData(SeatEffectData[] data) {
        this.data = data;
    }

    /* loaded from: classes2.dex */
    public class SeatEffectData {
        int fc;
        double gain;
        int index;
        int type;

        public SeatEffectData() {
        }

        public int getIndex() {
            return this.index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public int getType() {
            return this.type;
        }

        public void setType(int type) {
            this.type = type;
        }

        public int getFc() {
            return this.fc;
        }

        public void setFc(int fc) {
            this.fc = fc;
        }

        public double getGain() {
            return this.gain;
        }

        public void setGain(double gain) {
            this.gain = gain;
        }
    }
}
