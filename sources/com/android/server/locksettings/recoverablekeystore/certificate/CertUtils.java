package com.android.server.locksettings.recoverablekeystore.certificate;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.slice.SliceClientPermissions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
/* loaded from: classes.dex */
public final class CertUtils {
    private static final String CERT_FORMAT = "X.509";
    private static final String CERT_PATH_ALG = "PKIX";
    private static final String CERT_STORE_ALG = "Collection";
    static final int MUST_EXIST_AT_LEAST_ONE = 2;
    static final int MUST_EXIST_EXACTLY_ONE = 1;
    static final int MUST_EXIST_UNENFORCED = 0;
    private static final String SIGNATURE_ALG = "SHA256withRSA";

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    @interface MustExist {
    }

    private CertUtils() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static X509Certificate decodeCert(byte[] certBytes) throws CertParsingException {
        return decodeCert(new ByteArrayInputStream(certBytes));
    }

    static X509Certificate decodeCert(InputStream inStream) throws CertParsingException {
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance(CERT_FORMAT);
            try {
                return (X509Certificate) certFactory.generateCertificate(inStream);
            } catch (CertificateException e) {
                throw new CertParsingException(e);
            }
        } catch (CertificateException e2) {
            throw new RuntimeException(e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Element getXmlRootNode(byte[] xmlBytes) throws CertParsingException {
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
            document.getDocumentElement().normalize();
            return document.getDocumentElement();
        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new CertParsingException(e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static List<String> getXmlNodeContents(int mustExist, Element rootNode, String... nodeTags) throws CertParsingException {
        String expression = String.join(SliceClientPermissions.SliceAuthority.DELIMITER, nodeTags);
        XPath xPath = XPathFactory.newInstance().newXPath();
        try {
            NodeList nodeList = (NodeList) xPath.compile(expression).evaluate(rootNode, XPathConstants.NODESET);
            switch (mustExist) {
                case 0:
                    break;
                case 1:
                    if (nodeList.getLength() != 1) {
                        throw new CertParsingException("The XML file must contain exactly one node with the path " + expression);
                    }
                    break;
                case 2:
                    if (nodeList.getLength() == 0) {
                        throw new CertParsingException("The XML file must contain at least one node with the path " + expression);
                    }
                    break;
                default:
                    throw new UnsupportedOperationException("This value of MustExist is not supported: " + mustExist);
            }
            List<String> result = new ArrayList<>();
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                result.add(node.getTextContent().replaceAll("\\s", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS));
            }
            return result;
        } catch (XPathExpressionException e) {
            throw new CertParsingException(e);
        }
    }

    public static byte[] decodeBase64(String str) throws CertParsingException {
        try {
            return Base64.getDecoder().decode(str);
        } catch (IllegalArgumentException e) {
            throw new CertParsingException(e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void verifyRsaSha256Signature(PublicKey signerPublicKey, byte[] signature, byte[] signedBytes) throws CertValidationException {
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALG);
            try {
                verifier.initVerify(signerPublicKey);
                verifier.update(signedBytes);
                if (!verifier.verify(signature)) {
                    throw new CertValidationException("The signature is invalid");
                }
            } catch (InvalidKeyException | SignatureException e) {
                throw new CertValidationException(e);
            }
        } catch (NoSuchAlgorithmException e2) {
            throw new RuntimeException(e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static CertPath validateCert(Date validationDate, X509Certificate trustedRoot, List<X509Certificate> intermediateCerts, X509Certificate leafCert) throws CertValidationException {
        PKIXParameters pkixParams = buildPkixParams(validationDate, trustedRoot, intermediateCerts, leafCert);
        CertPath certPath = buildCertPath(pkixParams);
        try {
            CertPathValidator certPathValidator = CertPathValidator.getInstance(CERT_PATH_ALG);
            try {
                certPathValidator.validate(certPath, pkixParams);
                return certPath;
            } catch (InvalidAlgorithmParameterException | CertPathValidatorException e) {
                throw new CertValidationException(e);
            }
        } catch (NoSuchAlgorithmException e2) {
            throw new RuntimeException(e2);
        }
    }

    public static void validateCertPath(X509Certificate trustedRoot, CertPath certPath) throws CertValidationException {
        validateCertPath(null, trustedRoot, certPath);
    }

    @VisibleForTesting
    static void validateCertPath(Date validationDate, X509Certificate trustedRoot, CertPath certPath) throws CertValidationException {
        if (certPath.getCertificates().isEmpty()) {
            throw new CertValidationException("The given certificate path is empty");
        }
        if (!(certPath.getCertificates().get(0) instanceof X509Certificate)) {
            throw new CertValidationException("The given certificate path does not contain X509 certificates");
        }
        List<? extends Certificate> certificates = certPath.getCertificates();
        X509Certificate leafCert = (X509Certificate) certificates.get(0);
        validateCert(validationDate, trustedRoot, certificates.subList(1, certificates.size()), leafCert);
    }

    @VisibleForTesting
    static CertPath buildCertPath(PKIXParameters pkixParams) throws CertValidationException {
        try {
            CertPathBuilder certPathBuilder = CertPathBuilder.getInstance(CERT_PATH_ALG);
            try {
                return certPathBuilder.build(pkixParams).getCertPath();
            } catch (InvalidAlgorithmParameterException | CertPathBuilderException e) {
                throw new CertValidationException(e);
            }
        } catch (NoSuchAlgorithmException e2) {
            throw new RuntimeException(e2);
        }
    }

    @VisibleForTesting
    static PKIXParameters buildPkixParams(Date validationDate, X509Certificate trustedRoot, List<X509Certificate> intermediateCerts, X509Certificate leafCert) throws CertValidationException {
        Set<TrustAnchor> trustedAnchors = new HashSet<>();
        trustedAnchors.add(new TrustAnchor(trustedRoot, null));
        List<X509Certificate> certs = new ArrayList<>(intermediateCerts);
        certs.add(leafCert);
        try {
            CertStore certStore = CertStore.getInstance(CERT_STORE_ALG, new CollectionCertStoreParameters(certs));
            X509CertSelector certSelector = new X509CertSelector();
            certSelector.setCertificate(leafCert);
            try {
                PKIXBuilderParameters pkixParams = new PKIXBuilderParameters(trustedAnchors, certSelector);
                pkixParams.addCertStore(certStore);
                pkixParams.setDate(validationDate);
                pkixParams.setRevocationEnabled(false);
                return pkixParams;
            } catch (InvalidAlgorithmParameterException e) {
                throw new CertValidationException(e);
            }
        } catch (InvalidAlgorithmParameterException e2) {
            throw new CertValidationException(e2);
        } catch (NoSuchAlgorithmException e3) {
            throw new RuntimeException(e3);
        }
    }
}
