package com.android.server.pm;

import android.content.pm.PackageParser;
import android.content.pm.Signature;
import com.android.internal.util.XmlUtils;
import com.android.server.am.AssistDataRequester;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class PackageSignatures {
    PackageParser.SigningDetails mSigningDetails;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageSignatures(PackageSignatures orig) {
        if (orig != null && orig.mSigningDetails != PackageParser.SigningDetails.UNKNOWN) {
            this.mSigningDetails = new PackageParser.SigningDetails(orig.mSigningDetails);
        } else {
            this.mSigningDetails = PackageParser.SigningDetails.UNKNOWN;
        }
    }

    PackageSignatures(PackageParser.SigningDetails signingDetails) {
        this.mSigningDetails = signingDetails;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageSignatures() {
        this.mSigningDetails = PackageParser.SigningDetails.UNKNOWN;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeXml(XmlSerializer serializer, String tagName, ArrayList<Signature> writtenSignatures) throws IOException {
        if (this.mSigningDetails.signatures == null) {
            return;
        }
        serializer.startTag(null, tagName);
        serializer.attribute(null, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, Integer.toString(this.mSigningDetails.signatures.length));
        serializer.attribute(null, "schemeVersion", Integer.toString(this.mSigningDetails.signatureSchemeVersion));
        writeCertsListXml(serializer, writtenSignatures, this.mSigningDetails.signatures, false);
        if (this.mSigningDetails.pastSigningCertificates != null) {
            serializer.startTag(null, "pastSigs");
            serializer.attribute(null, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, Integer.toString(this.mSigningDetails.pastSigningCertificates.length));
            writeCertsListXml(serializer, writtenSignatures, this.mSigningDetails.pastSigningCertificates, true);
            serializer.endTag(null, "pastSigs");
        }
        serializer.endTag(null, tagName);
    }

    private void writeCertsListXml(XmlSerializer serializer, ArrayList<Signature> writtenSignatures, Signature[] signatures, boolean isPastSigs) throws IOException {
        for (Signature sig : signatures) {
            serializer.startTag(null, "cert");
            int sigHash = sig.hashCode();
            int numWritten = writtenSignatures.size();
            int j = 0;
            while (true) {
                if (j >= numWritten) {
                    break;
                }
                Signature writtenSig = writtenSignatures.get(j);
                if (writtenSig.hashCode() != sigHash || !writtenSig.equals(sig)) {
                    j++;
                } else {
                    serializer.attribute(null, AssistDataRequester.KEY_RECEIVER_EXTRA_INDEX, Integer.toString(j));
                    break;
                }
            }
            if (j >= numWritten) {
                writtenSignatures.add(sig);
                serializer.attribute(null, AssistDataRequester.KEY_RECEIVER_EXTRA_INDEX, Integer.toString(numWritten));
                serializer.attribute(null, "key", sig.toCharsString());
            }
            if (isPastSigs) {
                serializer.attribute(null, xpInputManagerService.InputPolicyKey.KEY_FLAGS, Integer.toString(sig.getFlags()));
            }
            serializer.endTag(null, "cert");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void readXml(XmlPullParser parser, ArrayList<Signature> readSignatures) throws IOException, XmlPullParserException {
        int signatureSchemeVersion;
        PackageParser.SigningDetails.Builder builder = new PackageParser.SigningDetails.Builder();
        String countStr = parser.getAttributeValue(null, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT);
        if (countStr == null) {
            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <sigs> has no count at " + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
            return;
        }
        int count = Integer.parseInt(countStr);
        String schemeVersionStr = parser.getAttributeValue(null, "schemeVersion");
        if (schemeVersionStr == null) {
            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <sigs> has no schemeVersion at " + parser.getPositionDescription());
            signatureSchemeVersion = 0;
        } else {
            int signatureSchemeVersion2 = Integer.parseInt(schemeVersionStr);
            signatureSchemeVersion = signatureSchemeVersion2;
        }
        builder.setSignatureSchemeVersion(signatureSchemeVersion);
        ArrayList<Signature> signatureList = new ArrayList<>();
        int pos = readCertsListXml(parser, readSignatures, signatureList, count, false, builder);
        Signature[] signatures = (Signature[]) signatureList.toArray(new Signature[signatureList.size()]);
        builder.setSignatures(signatures);
        if (pos < count) {
            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <sigs> count does not match number of  <cert> entries" + parser.getPositionDescription());
        }
        try {
            this.mSigningDetails = builder.build();
        } catch (CertificateException e) {
            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <sigs> unable to convert certificate(s) to public key(s).");
            this.mSigningDetails = PackageParser.SigningDetails.UNKNOWN;
        }
    }

    private int readCertsListXml(XmlPullParser parser, ArrayList<Signature> readSignatures, ArrayList<Signature> signatures, int count, boolean isPastSigs, PackageParser.SigningDetails.Builder builder) throws IOException, XmlPullParserException {
        String countStr;
        String str;
        int i;
        XmlPullParser xmlPullParser = parser;
        int outerDepth = parser.getDepth();
        PackageParser.SigningDetails.Builder builder2 = builder;
        int pos = 0;
        while (true) {
            int type = parser.next();
            if (type != 1 && (type != 3 || parser.getDepth() > outerDepth)) {
                if (type != 3 && type != 4) {
                    String tagName = parser.getName();
                    if (tagName.equals("cert")) {
                        if (pos < count) {
                            String index = xmlPullParser.getAttributeValue(null, AssistDataRequester.KEY_RECEIVER_EXTRA_INDEX);
                            if (index != null) {
                                boolean signatureParsed = false;
                                try {
                                    int idx = Integer.parseInt(index);
                                    String key = xmlPullParser.getAttributeValue(null, "key");
                                    if (key == null) {
                                        if (idx >= 0 && idx < readSignatures.size()) {
                                            Signature sig = readSignatures.get(idx);
                                            if (sig != null) {
                                                signatures.add(sig);
                                                signatureParsed = true;
                                            } else {
                                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <cert> index " + index + " is not defined at " + parser.getPositionDescription());
                                            }
                                        } else {
                                            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <cert> index " + index + " is out of bounds at " + parser.getPositionDescription());
                                        }
                                    } else {
                                        Signature sig2 = new Signature(key);
                                        while (readSignatures.size() < idx) {
                                            readSignatures.add(null);
                                        }
                                        readSignatures.add(sig2);
                                        signatures.add(sig2);
                                        signatureParsed = true;
                                    }
                                } catch (NumberFormatException e) {
                                    PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <cert> index " + index + " is not a number at " + parser.getPositionDescription());
                                } catch (IllegalArgumentException e2) {
                                    PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <cert> index " + index + " has an invalid signature at " + parser.getPositionDescription() + ": " + e2.getMessage());
                                }
                                if (isPastSigs) {
                                    String flagsStr = xmlPullParser.getAttributeValue(null, xpInputManagerService.InputPolicyKey.KEY_FLAGS);
                                    if (flagsStr != null) {
                                        try {
                                            int flagsValue = Integer.parseInt(flagsStr);
                                            if (signatureParsed) {
                                                signatures.get(signatures.size() - 1).setFlags(flagsValue);
                                            } else {
                                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: signature not available at index " + pos + " to set flags at " + parser.getPositionDescription());
                                            }
                                        } catch (NumberFormatException e3) {
                                            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <cert> flags " + flagsStr + " is not a number at " + parser.getPositionDescription());
                                        }
                                    } else {
                                        PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <cert> has no flags at " + parser.getPositionDescription());
                                    }
                                }
                            } else {
                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <cert> has no index at " + parser.getPositionDescription());
                            }
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: too many <cert> tags, expected " + count + " at " + parser.getPositionDescription());
                        }
                        pos++;
                        XmlUtils.skipCurrentTag(parser);
                    } else if (tagName.equals("pastSigs")) {
                        if (!isPastSigs) {
                            String countStr2 = xmlPullParser.getAttributeValue(null, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT);
                            if (countStr2 == null) {
                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <pastSigs> has no count at " + parser.getPositionDescription());
                                XmlUtils.skipCurrentTag(parser);
                            } else {
                                try {
                                    int pastSigsCount = Integer.parseInt(countStr2);
                                    ArrayList<Signature> pastSignatureList = new ArrayList<>();
                                    countStr = countStr2;
                                    str = " is not a number at ";
                                    try {
                                        int pastSigsPos = readCertsListXml(parser, readSignatures, pastSignatureList, pastSigsCount, true, builder2);
                                        Signature[] pastSignatures = (Signature[]) pastSignatureList.toArray(new Signature[pastSignatureList.size()]);
                                        builder2 = builder2.setPastSigningCertificates(pastSignatures);
                                        if (pastSigsPos < pastSigsCount) {
                                            i = 5;
                                            try {
                                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <pastSigs> count does not match number of <cert> entries " + parser.getPositionDescription());
                                            } catch (NumberFormatException e4) {
                                                PackageManagerService.reportSettingsProblem(i, "Error in package manager settings: <pastSigs> count " + countStr + str + parser.getPositionDescription());
                                                xmlPullParser = parser;
                                            }
                                        }
                                    } catch (NumberFormatException e5) {
                                        i = 5;
                                    }
                                } catch (NumberFormatException e6) {
                                    countStr = countStr2;
                                    str = " is not a number at ";
                                    i = 5;
                                }
                            }
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "<pastSigs> encountered multiple times under the same <sigs> at " + parser.getPositionDescription());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    } else {
                        PackageManagerService.reportSettingsProblem(5, "Unknown element under <sigs>: " + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                    xmlPullParser = parser;
                }
                xmlPullParser = parser;
            }
        }
        return pos;
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("PackageSignatures{");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append(" version:");
        buf.append(this.mSigningDetails.signatureSchemeVersion);
        buf.append(", signatures:[");
        if (this.mSigningDetails.signatures != null) {
            for (int i = 0; i < this.mSigningDetails.signatures.length; i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(Integer.toHexString(this.mSigningDetails.signatures[i].hashCode()));
            }
        }
        buf.append("]");
        buf.append(", past signatures:[");
        if (this.mSigningDetails.pastSigningCertificates != null) {
            for (int i2 = 0; i2 < this.mSigningDetails.pastSigningCertificates.length; i2++) {
                if (i2 > 0) {
                    buf.append(", ");
                }
                buf.append(Integer.toHexString(this.mSigningDetails.pastSigningCertificates[i2].hashCode()));
                buf.append(" flags: ");
                buf.append(Integer.toHexString(this.mSigningDetails.pastSigningCertificates[i2].getFlags()));
            }
        }
        buf.append("]}");
        return buf.toString();
    }
}
