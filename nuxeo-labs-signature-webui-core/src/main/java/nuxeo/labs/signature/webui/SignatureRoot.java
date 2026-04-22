/*
 * (C) Copyright 2026 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package nuxeo.labs.signature.webui;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_CONFLICT;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.platform.signature.api.exception.AlreadySignedException;
import org.nuxeo.ecm.platform.signature.api.exception.CertException;
import org.nuxeo.ecm.platform.signature.api.sign.SignatureService;
import org.nuxeo.ecm.platform.signature.api.sign.SignatureService.SigningDisposition;
import org.nuxeo.ecm.platform.signature.api.user.CUserService;
import org.nuxeo.ecm.platform.signature.core.sign.SignatureHelper;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.ecm.webengine.model.impl.ModuleRoot;
import org.nuxeo.runtime.api.Framework;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/**
 * WebEngine module root for the Digital Signature REST API.
 * <p>
 * Exposes signature endpoints at {@code /api/v1/signature/}.
 * <p>
 * Certificate management:
 * <ul>
 * <li>{@code GET /certificate} - check if current user has a certificate</li>
 * <li>{@code POST /certificate} - create a certificate for the current user</li>
 * <li>{@code DELETE /certificate} - delete current user's certificate</li>
 * <li>{@code GET /rootcertificate} - download the root CA certificate</li>
 * </ul>
 * Document signing:
 * <ul>
 * <li>{@code GET /document/{docId}} - get signing status and certificate list</li>
 * <li>{@code POST /document/{docId}} - sign a document</li>
 * </ul>
 *
 * @since 2025.16
 */
@Path("/api/v1/signature")
@WebObject(type = "SignatureRoot")
@Produces(MediaType.APPLICATION_JSON)
public class SignatureRoot extends ModuleRoot {

    private static final Logger log = LogManager.getLogger(SignatureRoot.class);

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    // ==================== Certificate Management ====================

    /**
     * Checks whether the current user has a certificate.
     */
    @GET
    @Path("certificate")
    public Response getCertificateStatus() throws IOException {
        var cUserService = Framework.getService(CUserService.class);
        var principal = (NuxeoPrincipal) getContext().getCoreSession().getPrincipal();
        boolean has = cUserService.hasCertificate(principal.getName());
        return jsonResponse(gen -> {
            gen.writeStartObject();
            gen.writeStringField("entity-type", "certificate-status");
            gen.writeBooleanField("hasCertificate", has);
            if (has) {
                writeCertificateInfo(gen, cUserService, principal);
            }
            gen.writeEndObject();
        });
    }

    /**
     * Checks whether a specific user has a certificate. Restricted to administrators.
     *
     * @since 2025.16
     */
    @GET
    @Path("certificate/{username}")
    public Response getUserCertificateStatus(@PathParam("username") String username) throws IOException {
        var principal = (NuxeoPrincipal) getContext().getCoreSession().getPrincipal();
        if (!principal.isAdministrator()) {
            throw new NuxeoException("Only administrators can check other users' certificates.", SC_FORBIDDEN);
        }
        var cUserService = Framework.getService(CUserService.class);
        boolean has = cUserService.hasCertificate(username);
        return jsonResponse(gen -> {
            gen.writeStartObject();
            gen.writeStringField("entity-type", "certificate-status");
            gen.writeStringField("username", username);
            gen.writeBooleanField("hasCertificate", has);
            gen.writeEndObject();
        });
    }

    /**
     * Creates a certificate for the current user.
     */
    @POST
    @Path("certificate")
    public Response createCertificate(@FormParam("password") String password,
            @FormParam("passwordConfirm") String passwordConfirm) throws IOException {
        if (password == null || password.length() < 8) {
            throw new NuxeoException("Password must be at least 8 characters long.", SC_BAD_REQUEST);
        }
        if (!password.equals(passwordConfirm)) {
            throw new NuxeoException("Passwords do not match.", SC_BAD_REQUEST);
        }
        var principal = (NuxeoPrincipal) getContext().getCoreSession().getPrincipal();
        if (password.equals(principal.getName())) {
            throw new NuxeoException("Password must be different from your login.", SC_BAD_REQUEST);
        }
        var userManager = Framework.getService(UserManager.class);
        var userModel = userManager.getUserModel(principal.getName());
        if (userModel == null) {
            throw new NuxeoException("User not found.", SC_NOT_FOUND);
        }
        var firstName = (String) userModel.getPropertyValue("user:firstName");
        var lastName = (String) userModel.getPropertyValue("user:lastName");
        var email = (String) userModel.getPropertyValue("user:email");
        if (isBlank(firstName) || isBlank(lastName) || isBlank(email)) {
            throw new NuxeoException(
                    "Your user profile must have a first name, last name, and email to generate a certificate.",
                    SC_BAD_REQUEST);
        }
        var cUserService = Framework.getService(CUserService.class);
        if (cUserService.hasCertificate(principal.getName())) {
            throw new NuxeoException("Certificate already exists for this user.", SC_CONFLICT);
        }
        try {
            cUserService.createCertificate(userModel, password);
        } catch (CertException e) {
            log.warn("Failed to create certificate for user: {}", principal.getName(), e);
            throw new NuxeoException("Failed to create certificate.", e, SC_BAD_REQUEST);
        }
        log.info("Certificate created for user: {}", principal.getName());
        return getCertificateStatus();
    }

    /**
     * Deletes the current user's certificate.
     */
    @DELETE
    @Path("certificate")
    public Response deleteCertificate() {
        var cUserService = Framework.getService(CUserService.class);
        var principal = (NuxeoPrincipal) getContext().getCoreSession().getPrincipal();
        if (!cUserService.hasCertificate(principal.getName())) {
            throw new NuxeoException("No certificate found for this user.", SC_NOT_FOUND);
        }
        cUserService.deleteCertificate(principal.getName());
        log.info("Certificate deleted for user: {}", principal.getName());
        return Response.noContent().build();
    }

    /**
     * Downloads the root CA certificate file.
     */
    @GET
    @Path("rootcertificate")
    @Produces("application/x-x509-ca-cert")
    public Response downloadRootCertificate() {
        var cUserService = Framework.getService(CUserService.class);
        byte[] rootCertData = cUserService.getRootCertificateData();
        return Response.ok(rootCertData, "application/x-x509-ca-cert")
                       .header("Content-Disposition", "attachment; filename=\"LOCAL_CA_.crt\"")
                       .build();
    }

    // ==================== Document Signing ====================

    /**
     * Gets the signing status for a document, including the list of existing signatures.
     */
    @GET
    @Path("document/{docId}")
    public Response getDocumentSigningStatus(@PathParam("docId") String docId) throws IOException {
        var session = getContext().getCoreSession();
        var doc = getDocument(session, docId);
        var signatureService = Framework.getService(SignatureService.class);
        var principal = (NuxeoPrincipal) session.getPrincipal();
        var userManager = Framework.getService(UserManager.class);
        var userModel = userManager.getUserModel(principal.getName());
        var statusWithBlob = signatureService.getSigningStatus(doc, userModel);
        var cUserService = Framework.getService(CUserService.class);
        boolean hasCert = cUserService.hasCertificate(principal.getName());
        boolean canSign = session.hasPermission(doc.getRef(), "Write");
        List<X509Certificate> certificates = signatureService.getCertificates(doc);

        return jsonResponse(gen -> {
            gen.writeStartObject();
            gen.writeStringField("entity-type", "signature-status");
            gen.writeStringField("docId", docId);
            gen.writeNumberField("signingStatus", statusWithBlob.getStatus());
            gen.writeBooleanField("hasCertificate", hasCert);
            gen.writeBooleanField("canSign", canSign);
            if (statusWithBlob.getBlob() != null) {
                gen.writeStringField("blobFilename", statusWithBlob.getBlob().getFilename());
                gen.writeStringField("blobPath", statusWithBlob.getPath());
            }
            gen.writeArrayFieldStart("certificates");
            for (var cert : certificates) {
                gen.writeStartObject();
                gen.writeStringField("subjectDN", cert.getSubjectDN().toString());
                gen.writeStringField("issuerDN", cert.getIssuerDN().toString());
                gen.writeStringField("notAfter", cert.getNotAfter().toString());
                gen.writeStringField("notBefore", cert.getNotBefore().toString());
                gen.writeStringField("serialNumber", cert.getSerialNumber().toString());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    /**
     * Signs a document with the current user's certificate.
     */
    @POST
    @Path("document/{docId}")
    public Response signDocument(@PathParam("docId") String docId, @FormParam("password") String password,
            @FormParam("reason") String reason) throws IOException {
        if (password == null || password.isEmpty()) {
            throw new NuxeoException("Certificate password is required.", SC_BAD_REQUEST);
        }
        var session = getContext().getCoreSession();
        var doc = getDocument(session, docId);
        if (!session.hasPermission(doc.getRef(), "Write")) {
            throw new NuxeoException("You do not have permission to sign this document.", SC_FORBIDDEN);
        }
        var principal = (NuxeoPrincipal) session.getPrincipal();
        var cUserService = Framework.getService(CUserService.class);
        if (!cUserService.hasCertificate(principal.getName())) {
            throw new NuxeoException("You must generate a certificate before signing.", SC_BAD_REQUEST);
        }
        var userManager = Framework.getService(UserManager.class);
        var userModel = userManager.getUserModel(principal.getName());
        var signatureService = Framework.getService(SignatureService.class);

        var bh = doc.getAdapter(BlobHolder.class);
        boolean originalIsPdf = bh != null && bh.getBlob() != null
                && "application/pdf".equals(bh.getBlob().getMimeType());
        boolean pdfa = SignatureHelper.getPDFA();
        var disposition = SignatureHelper.getDisposition(originalIsPdf);
        String archiveFilename = null;
        if (disposition == SigningDisposition.ARCHIVE && bh != null && bh.getBlob() != null) {
            archiveFilename = SignatureHelper.getArchiveFilename(bh.getBlob().getFilename());
        }

        try {
            signatureService.signDocument(doc, userModel, password, reason, pdfa, disposition, archiveFilename);
            session.saveDocument(doc);
        } catch (AlreadySignedException e) {
            log.warn("Document {} already signed by user {}", docId, principal.getName(), e);
            throw new NuxeoException("Document is already signed.", e, SC_CONFLICT);
        } catch (CertException e) {
            log.warn("Signing failed for document {} by user {}", docId, principal.getName(), e);
            throw new NuxeoException("Signing failed. Please check your certificate password.", e, SC_BAD_REQUEST);
        }

        log.info("Document {} signed by user {}", docId, principal.getName());
        return getDocumentSigningStatus(docId);
    }

    // ==================== Helpers ====================

    private DocumentModel getDocument(CoreSession session, String docId) {
        try {
            return session.getDocument(new IdRef(docId));
        } catch (DocumentNotFoundException e) {
            throw new NuxeoException("Document not found: " + docId, e, SC_NOT_FOUND);
        }
    }

    private void writeCertificateInfo(JsonGenerator gen, CUserService cUserService, NuxeoPrincipal principal)
            throws IOException {
        var certDoc = cUserService.getCertificate(principal.getName());
        if (certDoc != null) {
            var certInfo = (String) certDoc.getPropertyValue("cert:certificate");
            if (certInfo != null) {
                gen.writeStringField("certificateInfo", certInfo);
            }
            var startDate = certDoc.getPropertyValue("cert:startdate");
            if (startDate != null) {
                gen.writeStringField("startDate", startDate.toString());
            }
            var endDate = certDoc.getPropertyValue("cert:enddate");
            if (endDate != null) {
                gen.writeStringField("endDate", endDate.toString());
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @FunctionalInterface
    interface JsonWriter {
        void write(JsonGenerator gen) throws IOException;
    }

    private Response jsonResponse(JsonWriter writer) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gen = JSON_FACTORY.createGenerator(baos)) {
            writer.write(gen);
        }
        return Response.ok(baos.toString(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON).build();
    }
}
