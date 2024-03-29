package com.crivano.demoiselle.rest.server;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.cms.*;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.*;
import org.demoiselle.signer.core.ca.manager.CAManagerConfiguration;
import org.demoiselle.signer.core.util.MessagesBundle;
import org.demoiselle.signer.policy.engine.factory.PolicyFactory.Policies;
import org.demoiselle.signer.policy.impl.cades.SignerAlgorithmEnum;
import org.demoiselle.signer.policy.impl.cades.SignerException;
import org.demoiselle.signer.policy.impl.cades.pkcs7.impl.CAdESSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;

public class DemoiselleHelper {

    static {
        CAManagerConfiguration config = CAManagerConfiguration.getInstance();
        config.setCached(true);
    }

    private static final Logger logger = LoggerFactory.getLogger(DemoiselleHelper.class);

    /**
     * Recebe o certificado, o hash do documento, que pode ser sha256 para as
     * políticas atuais, o identificador da política a ser utilizada e a data e hora
     * da assinatura. Produz um array de bytes contendo a tabela de atributos
     * assinados.
     */
    public static byte[] produceSignedAttributes(X509Certificate certificate, byte[] hash,
                                                 SignerAlgorithmEnum algorithm, Policies policy, Date signingTime) throws Exception {
        CAdESSigner signer = new CAdESSigner(algorithm.getAlgorithm(), policy);
        signer.setCertificates(new Certificate[]{certificate});
        signer.setHash(hash);
        AttributeTable sa = signer.prepareSignedAttributes(null, null);

        ASN1EncodableVector vector = buildSignedAttributesTable(sa, algorithm, signingTime);
        return vectorToByteArray(vector);
    }

    /**
     * Recebe o certificado, o hash do documento, o identificador da política a ser
     * utilizada, a data e hora da assinatura e um array de bytes contento a
     * assinatura em si. Produz um array de bytes contendo o envelope CMS/PKCS7 da
     * assinatura.
     *
     * @throws Exception
     */
    public static byte[] produceEnvelope(X509Certificate certificate, byte[] hash, SignerAlgorithmEnum algorithm,
                                         Policies policy, Date signingTime, byte[] signature) throws Exception {
        CAdESSigner signer = new CAdESSigner(algorithm.getAlgorithm(), policy);
        signer.setCertificates(new Certificate[]{certificate});
        signer.setAttached(false);
        signer.setHash(hash);
        AttributeTable sa = signer.prepareSignedAttributes(null, null);
        Certificate[] certStore = signer.extractCertificates(null);

        // Construir um SignerInfo
//		Attributes attrs = buildSignedAttributesTable(sa, algorithm, signingTime).toASN1Structure();
        Attributes attrs = Attributes.getInstance(new DERSet(buildSignedAttributesTable(sa, algorithm, signingTime)));
        X509CertificateHolder cert = new X509CertificateHolder(certificate.getEncoded());
        SignerIdentifier sid = new SignerIdentifier(
                new IssuerAndSerialNumber(cert.getIssuer(), cert.getSerialNumber()));
        SignerInfo si = new SignerInfo(sid, algorithmByOID(algorithm.getOIDAlgorithmHash()), attrs,
                algorithmByOID("1.2.840.113549.1.1.11"), new DEROctetString(signature), null);

        // Construir o CMSSignedData
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        try {
            gen.addCertificates(signer.generatedCertStore(certStore));
        } catch (CMSException e) {
            logger.error(e.getMessage());
            throw new SignerException(e);
        }

        SignerInformationStore siStore = new SignerInformationStore(
                constructSignerInformation(si, ContentInfo.digestedData, null, hash));

        gen.addSigners(siStore);

        CMSTypedData cmsTypedData = new CMSAbsentContent();

        CMSSignedData cmsSignedData;
        try {
            cmsSignedData = gen.generate(cmsTypedData, signer.isAttached());
        } catch (CMSException e) {
            logger.error(e.getMessage());
            throw new SignerException(e);
        }
        return signer.envelopSignature(cmsSignedData, null);
    }

    private static SignerInformation constructSignerInformation(SignerInfo info, ASN1ObjectIdentifier contentType,
                                                                CMSProcessable content, byte[] resultDigest) throws Exception {
        Class fooClazz = SignerInformation.class;
        Constructor<SignerInformation> constructor = fooClazz.getDeclaredConstructor(SignerInfo.class,
                ASN1ObjectIdentifier.class, CMSProcessable.class, byte[].class);
        constructor.setAccessible(true);
        SignerInformation obj = constructor.newInstance(info, contentType, content, resultDigest);
        return obj;
    }

    private static byte[] vectorToByteArray(ASN1EncodableVector vector) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ASN1OutputStream asnOS = new ASN1OutputStream(baos);
        asnOS.writeObject(new DERSet(vector));
        asnOS.flush();
        return baos.toByteArray();
    }

    private static ASN1EncodableVector buildSignedAttributesTable(AttributeTable sa, SignerAlgorithmEnum algorithm,
                                                                  Date signingTime) {
        HashMap<Object, Object> parameters = new HashMap<>();
        parameters.put(CMSAttributeTableGenerator.DIGEST_ALGORITHM_IDENTIFIER,
                algorithmByOID(algorithm.getOIDAlgorithmHash()));
        parameters.put(CMSAttributeTableGenerator.SIGNATURE_ALGORITHM_IDENTIFIER,
                algorithmByOID(algorithm.getOIDAlgorithmCipher()));

        AttributeTable table = sa.add(CMSAttributes.signingTime, new Time(signingTime));

        CMSAttributeTableGenerator signedAttributeGenerator = new DefaultSignedAttributeTableGenerator(table);
        parameters.put(CMSAttributeTableGenerator.SIGNATURE_ALGORITHM_IDENTIFIER,
                algorithmByOID(algorithm.getOIDAlgorithmCipher()));

        AttributeTable attributes = signedAttributeGenerator.getAttributes(parameters);

        // Reorder attributes
        //
        ASN1EncodableVector ordered = new ASN1EncodableVector();
        ordered.add(attributes.get(CMSAttributes.contentType));
        ordered.add(attributes.get(CMSAttributes.signingTime));
        ordered.add(attributes.get(CMSAttributes.messageDigest));
        ordered.add(attributes.get(PKCSObjectIdentifiers.id_aa_ets_sigPolicyId));
        ordered.add(attributes.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2));

        return ordered;
    }

    private static org.bouncycastle.asn1.x509.AlgorithmIdentifier algorithmByOID(String oid) {
        return new org.bouncycastle.asn1.x509.AlgorithmIdentifier(new ASN1ObjectIdentifier(oid));
    }

}
