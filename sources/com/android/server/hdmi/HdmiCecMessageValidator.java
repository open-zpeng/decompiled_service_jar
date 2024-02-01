package com.android.server.hdmi;

import android.net.util.NetworkConstants;
import android.util.SparseArray;
/* loaded from: classes.dex */
public final class HdmiCecMessageValidator {
    private static final int DEST_ALL = 3;
    private static final int DEST_BROADCAST = 2;
    private static final int DEST_DIRECT = 1;
    static final int ERROR_DESTINATION = 2;
    static final int ERROR_PARAMETER = 3;
    static final int ERROR_PARAMETER_SHORT = 4;
    static final int ERROR_SOURCE = 1;
    static final int OK = 0;
    private static final int SRC_UNREGISTERED = 4;
    private static final String TAG = "HdmiCecMessageValidator";
    private final HdmiControlService mService;
    final SparseArray<ValidationInfo> mValidationInfo = new SparseArray<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface ParameterValidator {
        int isValid(byte[] bArr);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ValidationInfo {
        public final int addressType;
        public final ParameterValidator parameterValidator;

        public ValidationInfo(ParameterValidator validator, int type) {
            this.parameterValidator = validator;
            this.addressType = type;
        }
    }

    public HdmiCecMessageValidator(HdmiControlService service) {
        this.mService = service;
        PhysicalAddressValidator physicalAddressValidator = new PhysicalAddressValidator();
        addValidationInfo(130, physicalAddressValidator, 6);
        addValidationInfo(157, physicalAddressValidator, 1);
        addValidationInfo(132, new ReportPhysicalAddressValidator(), 6);
        addValidationInfo(128, new RoutingChangeValidator(), 6);
        addValidationInfo(NetworkConstants.ICMPV6_ECHO_REPLY_TYPE, physicalAddressValidator, 6);
        addValidationInfo(NetworkConstants.ICMPV6_ROUTER_ADVERTISEMENT, physicalAddressValidator, 2);
        addValidationInfo(112, new SystemAudioModeRequestValidator(), 1);
        FixedLengthValidator noneValidator = new FixedLengthValidator(0);
        addValidationInfo(255, noneValidator, 1);
        addValidationInfo(159, noneValidator, 1);
        addValidationInfo(HdmiCecKeycode.UI_BROADCAST_DIGITAL_COMMNICATIONS_SATELLITE_2, noneValidator, 5);
        addValidationInfo(113, noneValidator, 1);
        addValidationInfo(143, noneValidator, 1);
        addValidationInfo(140, noneValidator, 5);
        addValidationInfo(70, noneValidator, 1);
        addValidationInfo(131, noneValidator, 5);
        addValidationInfo(125, noneValidator, 1);
        addValidationInfo(4, noneValidator, 1);
        addValidationInfo(192, noneValidator, 1);
        addValidationInfo(11, noneValidator, 1);
        addValidationInfo(15, noneValidator, 1);
        addValidationInfo(HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_STEP_PLUS, noneValidator, 1);
        addValidationInfo(HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_NEUTRAL, noneValidator, 1);
        addValidationInfo(HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_STEP_MINUS, noneValidator, 1);
        addValidationInfo(196, noneValidator, 1);
        addValidationInfo(NetworkConstants.ICMPV6_ROUTER_SOLICITATION, noneValidator, 6);
        addValidationInfo(54, noneValidator, 7);
        addValidationInfo(197, noneValidator, 1);
        addValidationInfo(13, noneValidator, 1);
        addValidationInfo(6, noneValidator, 1);
        addValidationInfo(5, noneValidator, 1);
        addValidationInfo(69, noneValidator, 1);
        addValidationInfo(139, noneValidator, 3);
        FixedLengthValidator oneByteValidator = new FixedLengthValidator(1);
        addValidationInfo(9, new VariableLengthValidator(1, 8), 1);
        addValidationInfo(10, oneByteValidator, 1);
        addValidationInfo(158, oneByteValidator, 1);
        addValidationInfo(50, new FixedLengthValidator(3), 2);
        VariableLengthValidator maxLengthValidator = new VariableLengthValidator(0, 14);
        addValidationInfo(NetworkConstants.ICMPV6_NEIGHBOR_SOLICITATION, new FixedLengthValidator(3), 2);
        addValidationInfo(137, new VariableLengthValidator(1, 14), 5);
        addValidationInfo(160, new VariableLengthValidator(4, 14), 7);
        addValidationInfo(138, maxLengthValidator, 7);
        addValidationInfo(100, maxLengthValidator, 1);
        addValidationInfo(71, maxLengthValidator, 1);
        addValidationInfo(141, oneByteValidator, 1);
        addValidationInfo(142, oneByteValidator, 1);
        addValidationInfo(68, new VariableLengthValidator(1, 2), 1);
        addValidationInfo(144, oneByteValidator, 1);
        addValidationInfo(0, new FixedLengthValidator(2), 1);
        addValidationInfo(122, oneByteValidator, 1);
        addValidationInfo(163, new FixedLengthValidator(3), 1);
        addValidationInfo(164, oneByteValidator, 1);
        addValidationInfo(114, oneByteValidator, 3);
        addValidationInfo(126, oneByteValidator, 1);
        addValidationInfo(154, oneByteValidator, 1);
        addValidationInfo(248, maxLengthValidator, 6);
    }

    private void addValidationInfo(int opcode, ParameterValidator validator, int addrType) {
        this.mValidationInfo.append(opcode, new ValidationInfo(validator, addrType));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int isValid(HdmiCecMessage message) {
        int opcode = message.getOpcode();
        ValidationInfo info = this.mValidationInfo.get(opcode);
        if (info == null) {
            HdmiLogger.warning("No validation information for the message: " + message, new Object[0]);
            return 0;
        } else if (message.getSource() == 15 && (info.addressType & 4) == 0) {
            HdmiLogger.warning("Unexpected source: " + message, new Object[0]);
            return 1;
        } else {
            if (message.getDestination() == 15) {
                if ((info.addressType & 2) == 0) {
                    HdmiLogger.warning("Unexpected broadcast message: " + message, new Object[0]);
                    return 2;
                }
            } else if ((info.addressType & 1) == 0) {
                HdmiLogger.warning("Unexpected direct message: " + message, new Object[0]);
                return 2;
            }
            int errorCode = info.parameterValidator.isValid(message.getParams());
            if (errorCode == 0) {
                return 0;
            }
            HdmiLogger.warning("Unexpected parameters: " + message, new Object[0]);
            return errorCode;
        }
    }

    /* loaded from: classes.dex */
    private static class FixedLengthValidator implements ParameterValidator {
        private final int mLength;

        public FixedLengthValidator(int length) {
            this.mLength = length;
        }

        @Override // com.android.server.hdmi.HdmiCecMessageValidator.ParameterValidator
        public int isValid(byte[] params) {
            return params.length < this.mLength ? 4 : 0;
        }
    }

    /* loaded from: classes.dex */
    private static class VariableLengthValidator implements ParameterValidator {
        private final int mMaxLength;
        private final int mMinLength;

        public VariableLengthValidator(int minLength, int maxLength) {
            this.mMinLength = minLength;
            this.mMaxLength = maxLength;
        }

        @Override // com.android.server.hdmi.HdmiCecMessageValidator.ParameterValidator
        public int isValid(byte[] params) {
            return params.length < this.mMinLength ? 4 : 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isValidPhysicalAddress(byte[] params, int offset) {
        if (this.mService.isTvDevice()) {
            int path = HdmiUtils.twoBytesToInt(params, offset);
            if (path == 65535 || path != this.mService.getPhysicalAddress()) {
                int portId = this.mService.pathToPortId(path);
                return portId != -1;
            }
            return true;
        }
        return true;
    }

    static boolean isValidType(int type) {
        return type >= 0 && type <= 7 && type != 2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int toErrorCode(boolean success) {
        return success ? 0 : 3;
    }

    /* loaded from: classes.dex */
    private class PhysicalAddressValidator implements ParameterValidator {
        private PhysicalAddressValidator() {
        }

        @Override // com.android.server.hdmi.HdmiCecMessageValidator.ParameterValidator
        public int isValid(byte[] params) {
            if (params.length >= 2) {
                return HdmiCecMessageValidator.toErrorCode(HdmiCecMessageValidator.this.isValidPhysicalAddress(params, 0));
            }
            return 4;
        }
    }

    /* loaded from: classes.dex */
    private class SystemAudioModeRequestValidator extends PhysicalAddressValidator {
        private SystemAudioModeRequestValidator() {
            super();
        }

        @Override // com.android.server.hdmi.HdmiCecMessageValidator.PhysicalAddressValidator, com.android.server.hdmi.HdmiCecMessageValidator.ParameterValidator
        public int isValid(byte[] params) {
            if (params.length == 0) {
                return 0;
            }
            return super.isValid(params);
        }
    }

    /* loaded from: classes.dex */
    private class ReportPhysicalAddressValidator implements ParameterValidator {
        private ReportPhysicalAddressValidator() {
        }

        @Override // com.android.server.hdmi.HdmiCecMessageValidator.ParameterValidator
        public int isValid(byte[] params) {
            if (params.length >= 3) {
                boolean z = false;
                if (HdmiCecMessageValidator.this.isValidPhysicalAddress(params, 0) && HdmiCecMessageValidator.isValidType(params[2])) {
                    z = true;
                }
                return HdmiCecMessageValidator.toErrorCode(z);
            }
            return 4;
        }
    }

    /* loaded from: classes.dex */
    private class RoutingChangeValidator implements ParameterValidator {
        private RoutingChangeValidator() {
        }

        @Override // com.android.server.hdmi.HdmiCecMessageValidator.ParameterValidator
        public int isValid(byte[] params) {
            if (params.length < 4) {
                return 4;
            }
            boolean z = false;
            if (HdmiCecMessageValidator.this.isValidPhysicalAddress(params, 0) && HdmiCecMessageValidator.this.isValidPhysicalAddress(params, 2)) {
                z = true;
            }
            return HdmiCecMessageValidator.toErrorCode(z);
        }
    }
}
