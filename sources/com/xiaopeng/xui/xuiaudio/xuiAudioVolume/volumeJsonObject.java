package com.xiaopeng.xui.xuiaudio.xuiAudioVolume;
/* loaded from: classes.dex */
public class volumeJsonObject {

    /* loaded from: classes.dex */
    public class streamVolumePare {
        public int maxStreamType;
        public int maxVolType;
        public double version;
        public volumeTypePare[] volumeTypeList;

        public streamVolumePare() {
        }

        public double getVersion() {
            return this.version;
        }

        public void setVersion(double version) {
            this.version = version;
        }

        public int getMaxVolType() {
            return this.maxVolType;
        }

        public void setMaxVolType(int maxVolType) {
            this.maxVolType = maxVolType;
        }

        public int getMaxStreamType() {
            return this.maxStreamType;
        }

        public void setMaxStreamType(int maxStreamType) {
            this.maxStreamType = maxStreamType;
        }

        public volumeTypePare[] getVolumeTypeList() {
            return this.volumeTypeList;
        }

        public void setVolumeTypeList(volumeTypePare[] volumeTypeList) {
            this.volumeTypeList = volumeTypeList;
        }

        /* loaded from: classes.dex */
        public class volumeTypePare {
            public String mode;
            public String streamType;
            public int streamTypeIndex;
            public String volumeType;
            public int volumeTypeIndex;

            public volumeTypePare() {
            }

            public String getStreamType() {
                return this.streamType;
            }

            public void setStreamType(String streamType) {
                this.streamType = streamType;
            }

            public int getStreamTypeIndex() {
                return this.streamTypeIndex;
            }

            public void setStreamTypeIndex(int streamTypeIndex) {
                this.streamTypeIndex = streamTypeIndex;
            }

            public String getMode() {
                return this.mode;
            }

            public void setMode(String mode) {
                this.mode = mode;
            }

            public String getVolumeType() {
                return this.volumeType;
            }

            public void setVolumeType(String volumeType) {
                this.volumeType = volumeType;
            }

            public void setVolumeTypeIndex(int volumeTypeIndex) {
                this.volumeTypeIndex = volumeTypeIndex;
            }

            public int getVolumeTypeIndex() {
                return this.volumeTypeIndex;
            }
        }
    }

    /* loaded from: classes.dex */
    public class conflictVolumeObject {
        public conflictVolumePolicy[] policy;
        public double version;

        public conflictVolumeObject() {
        }

        public double getVersion() {
            return this.version;
        }

        public void setVersion(double version) {
            this.version = version;
        }

        public conflictVolumePolicy[] getPolicy() {
            return this.policy;
        }

        public void setPolicy(conflictVolumePolicy[] policy) {
            this.policy = policy;
        }

        /* loaded from: classes.dex */
        public class conflictVolumePolicy {
            public int ampLevel;
            public EnvTrigger[] envtriggerChange;
            public int screenId;
            public StreamTypeTrigger[] streamTypeChange;

            public conflictVolumePolicy() {
            }

            public int getScreenId() {
                return this.screenId;
            }

            public void setScreenId(int screenId) {
                this.screenId = screenId;
            }

            public int getAmpLevel() {
                return this.ampLevel;
            }

            public void setAmpLevel(int ampLevel) {
                this.ampLevel = ampLevel;
            }

            public StreamTypeTrigger[] getStreamTypeChange() {
                return this.streamTypeChange;
            }

            public void setStreamTypeChange(StreamTypeTrigger[] streamTypeChange) {
                this.streamTypeChange = streamTypeChange;
            }

            public EnvTrigger[] getEnvtriggerChange() {
                return this.envtriggerChange;
            }

            public void setEnvtriggerChange(EnvTrigger[] envtriggerChange) {
                this.envtriggerChange = envtriggerChange;
            }

            /* loaded from: classes.dex */
            public class StreamTypeTrigger {
                public String extModeName;
                public String modeName;
                public boolean needRestore;
                public conflictPolicy[] policy;
                public String streamType;

                public StreamTypeTrigger() {
                }

                public String getStreamType() {
                    return this.streamType;
                }

                public void setStreamType(String streamType) {
                    this.streamType = streamType;
                }

                public boolean isNeedRestore() {
                    return this.needRestore;
                }

                public void setNeedRestore(boolean needRestore) {
                    this.needRestore = needRestore;
                }

                public conflictPolicy[] getPolicy() {
                    return this.policy;
                }

                public void setPolicy(conflictPolicy[] policy) {
                    this.policy = policy;
                }
            }

            /* loaded from: classes.dex */
            public class EnvTrigger {
                public String extModeName;
                public String modeName;
                public boolean needRestore;
                public conflictPolicy[] policy;
                public String trigger;
                public int triggerFlag;
                public int[] value;

                public EnvTrigger() {
                }

                public String getTrigger() {
                    return this.trigger;
                }

                public String getModeName() {
                    return this.modeName;
                }

                public void setModeName(String modeName) {
                    this.modeName = modeName;
                }

                public String getExtModeName() {
                    return this.extModeName;
                }

                public void setExtModeName(String extModeName) {
                    this.extModeName = extModeName;
                }

                public void setTrigger(String trigger) {
                    this.trigger = trigger;
                }

                public int getTriggerFlag() {
                    return this.triggerFlag;
                }

                public void setTriggerFlag(int triggerFlag) {
                    this.triggerFlag = triggerFlag;
                }

                public int[] getValue() {
                    return this.value;
                }

                public void setValue(int[] value) {
                    this.value = value;
                }

                public boolean isNeedRestore() {
                    return this.needRestore;
                }

                public void setNeedRestore(boolean needRestore) {
                    this.needRestore = needRestore;
                }

                public conflictPolicy[] getPolicy() {
                    return this.policy;
                }

                public void setPolicy(conflictPolicy[] policy) {
                    this.policy = policy;
                }
            }

            /* loaded from: classes.dex */
            public class conflictPolicy {
                public int extModeIndex;
                public int index;
                public int modeIndex;
                public conflictModePolicy[] modePolicy;

                public conflictPolicy() {
                }

                public int getIndex() {
                    return this.index;
                }

                public void setIndex(int index) {
                    this.index = index;
                }

                public int getModeIndex() {
                    return this.modeIndex;
                }

                public void setMode(int modeIndex) {
                    this.modeIndex = modeIndex;
                }

                public int getExtMode() {
                    return this.extModeIndex;
                }

                public void setExtModeIndex(int extModeIndex) {
                    this.extModeIndex = extModeIndex;
                }

                public conflictModePolicy[] getModePolicy() {
                    return this.modePolicy;
                }

                public void setModePolicy(conflictModePolicy[] modePolicy) {
                    this.modePolicy = modePolicy;
                }
            }

            /* loaded from: classes.dex */
            public class conflictModePolicy {
                public int changeMode;
                public String changeType;
                public String changeValue;
                public String targetStream;

                public conflictModePolicy() {
                }

                public String getTargetStream() {
                    return this.targetStream;
                }

                public void setTargetStream(String targetStream) {
                    this.targetStream = targetStream;
                }

                public String getChangeType() {
                    return this.changeType;
                }

                public void setChangeType(String changeType) {
                    this.changeType = changeType;
                }

                public int getChangeMode() {
                    return this.changeMode;
                }

                public void setChangeMode(int changeMode) {
                    this.changeMode = changeMode;
                }

                public String getChangeValue() {
                    return this.changeValue;
                }

                public void setChangeValue(String changeValue) {
                    this.changeValue = changeValue;
                }
            }
        }
    }

    /* loaded from: classes.dex */
    public class volumeTypeData {
        public int maxVol;
        public int minVol;
        public double version;
        public VolumeTypeData[] volumeTypeDataList;

        public volumeTypeData() {
        }

        public double getVersion() {
            return this.version;
        }

        public void setVersion(double version) {
            this.version = version;
        }

        public int getMaxVol() {
            return this.maxVol;
        }

        public void setMaxVol(int maxVol) {
            this.maxVol = maxVol;
        }

        public int getMinVol() {
            return this.minVol;
        }

        public void setMinVol(int minVol) {
            this.minVol = minVol;
        }

        public VolumeTypeData[] getVolumeTypeDataList() {
            return this.volumeTypeDataList;
        }

        public void setVolumeTypeDataList(VolumeTypeData[] volumeTypeDataList) {
            this.volumeTypeDataList = volumeTypeDataList;
        }

        /* loaded from: classes.dex */
        public class VolumeTypeData {
            public int groupId;
            public boolean isFixed;
            public String mode;
            public int priority;
            public String property;
            public String volumeType;
            public VolumeValue[] volumeValue;

            public VolumeTypeData() {
            }

            public String getMode() {
                return this.mode;
            }

            public void setMode(String mode) {
                this.mode = mode;
            }

            public String getVolumeType() {
                return this.volumeType;
            }

            public void setVolumeType(String volumeType) {
                this.volumeType = volumeType;
            }

            public boolean getIsFixed() {
                return this.isFixed;
            }

            public void setFixed(boolean fixed) {
                this.isFixed = fixed;
            }

            public int getPriority() {
                return this.priority;
            }

            public void setPriority(int priority) {
                this.priority = priority;
            }

            public String getProperty() {
                return this.property;
            }

            public void setProperty(String property) {
                this.property = property;
            }

            public int getGroupId() {
                return this.groupId;
            }

            public void setGroupId(int groupId) {
                this.groupId = groupId;
            }

            public VolumeValue[] getVolumeValue() {
                return this.volumeValue;
            }

            public void setVolumeValue(VolumeValue[] volumeValue) {
                this.volumeValue = volumeValue;
            }
        }

        /* loaded from: classes.dex */
        public class VolumeValue {
            public int defaultVal;
            public int modeindex;

            public VolumeValue() {
            }

            public int getModeindex() {
                return this.modeindex;
            }

            public void setModeindex(int modeindex) {
                this.modeindex = modeindex;
            }

            public int getDefaultVal() {
                return this.defaultVal;
            }

            public void setDefaultVal(int defaultVal) {
                this.defaultVal = defaultVal;
            }
        }
    }
}
