# Possible Improvements

> [!IMPORTANT]
> These are ideas under consideration — there is no commitment to implementing them at any particular time. That said, pull requests are welcome! 😊

## Custom Signature Page

By default, `nuxeo-signature` places visual signatures on page 1 of the document, overlapping the content. A cleaner approach would be to prepend a dedicated signature page before signing.

**Design:** A Framework property `org.nuxeo.ecm.signature.signOnNewPage.operation` would hold the ID of an Automation operation. Before the first signature on a document:

1. If the property is not set → current behavior (no extra page)
2. If set → call the operation with the current `Document` as input
3. If the operation returns a `Blob` (single-page PDF) → prepend it as page 1
4. If it returns `null` → no page added, current behavior
5. Proceed with signing — the signature naturally lands on the new page 1 (since `nuxeo-signature` always signs on page 1)

This would let customers generate a branded page (logo, document title, "SIGNATURES" header, etc.) via a Studio automation chain or scripting, while keeping the default behavior unchanged for everyone else.

> [!NOTE]
> This approach works for a "first page" strategy because `nuxeo-signature` hardcodes `PAGE_TO_SIGN = 1`. A "last page" strategy would require overriding `SignatureServiceImpl`, which is more invasive.

## Migrate REST API to Automation operations + content enrichers

*Considered: 2026-05-08. Status: undecided. Plugin version at the time: `2025.2.0-SNAPSHOT`, parent `org.nuxeo:nuxeo-parent:2025.16`.*

The plugin currently exposes a custom WebEngine REST API at `/api/v1/signature/` (see `SignatureRoot.java`). The Polymer elements call it via `iron-ajax`. An alternative would be to use the standard Nuxeo Automation framework (operations + `<nuxeo-operation>`) plus content enrichers for read-only state.

### Why consider it

- **Idiomatic for Web UI** — `<nuxeo-operation>` is the standard pattern; reusable from Studio chains, the JS/Python SDKs, the `/automation` REST endpoint, Nuxeo CLI.
- **Free auth, CSRF, transactions, error envelope, marshalling** — Automation handles all of this; the current REST endpoint reimplements parts (e.g. the `jsonResponse()` helper).
- **Discoverable** — operations show up in `/nuxeo/site/automation/doc` and Studio's operation picker.
- **Blob downloads work natively** — the root-certificate download becomes an operation returning a `Blob`; framework handles HTTP response and `Content-Disposition`.
- **Content enrichers** can carry read-only state (`hasCertificate`, `signatureStatus`) directly inside the standard document/user JSON, eliminating extra round-trips.

### Why not (yet)

- The current code works.
- Operations are POST-only in practice — current `GET` reads (`/certificate`, `/document/{docId}` status) become POSTs, which is semantically off (minor).
- Default Automation error envelope leaks exception class + message; the current code carefully sanitizes these. Each operation would need a thin try/catch to preserve that.
- Migration cost: ~7 new Java files, 2 Polymer rewrites, a new test module — probably half a day to a day of work, with no tests today to catch regressions during the move.
- Custom URLs (`/api/v1/signature/...`) would be gone. Confirmed acceptable: plugin is new, not on Marketplace, no external consumers.

### Per-endpoint verdict

| Current endpoint | Better as Automation? | Notes |
|------------------|----------------------|-------|
| `POST /document/{docId}` (sign) | **Yes** | Action on a document, returns updated doc — perfect fit |
| `GET /document/{docId}` (status + signatures) | **Enricher** | `signatureStatus` enricher — eliminates a round-trip |
| `POST /certificate` (generate) | **Yes** | Action |
| `DELETE /certificate` | **Yes** | Action |
| `GET /certificate` (current user has cert?) | **Enricher** | `hasCertificate` enricher on `NuxeoPrincipal` JSON |
| `GET /certificate/{username}` (admin-only) | **Operation** with optional `username` param | `requires = "administrators"` when `username` is provided |
| `GET /rootcertificate` (download CA) | **Operation** returning `Blob`, or just a `DownloadService` URL | Either is fine |

### Migration plan (if decided)

#### Backend

New Java files under `nuxeo/labs/signature/webui/operations/`:

1. `SignDocumentOp.java` — `Signature.SignDocument`. Input: `DocumentModel`, params: `password`, `reason`. Returns updated `DocumentModel`.
2. `GenerateCertificateOp.java` — `Signature.GenerateCertificate`. Params: `password`, `passwordConfirm`.
3. `DeleteCertificateOp.java` — `Signature.DeleteCertificate`.
4. `HasCertificateOp.java` — `Signature.HasCertificate`. Optional `username` param (admin-only when set and != caller).
5. `GetRootCertificateOp.java` — `Signature.GetRootCertificate`. Returns `Blob`.

New Java files under `nuxeo/labs/signature/webui/enrichers/`:

6. `SignatureStatusEnricher.java` — `AbstractJsonEnricher<DocumentModel>`, name `signatureStatus`. Emits `{ "signed": bool, "signatures": [...] }`.
7. `HasCertificateEnricher.java` — `AbstractJsonEnricher<NuxeoPrincipal>`, name `hasCertificate`. Emits a boolean.

XML / manifest:

8. New `OSGI-INF/operations-contrib.xml` registering each operation on `OperationServiceComponent`.
9. New `OSGI-INF/enrichers-contrib.xml` registering both enrichers on the `MarshallerRegistry` `enrichers` extension point.
10. `MANIFEST.MF` — add the two new component XMLs to `Nuxeo-Component` (one per line, single leading space on continuation lines, trailing newline at EOF).
11. `MANIFEST.MF` — **remove** the `Nuxeo-WebModule` line.

Deletions / cleanup:

- Delete `SignatureRoot.java`.
- Drop `nuxeo-webengine-core` and `nuxeo-platform-web-common` dependencies from the core POM if a clean build confirms they are no longer needed.

POM additions:

- Add `org.nuxeo.ecm.automation:nuxeo-automation-core` (`provided`).

#### Frontend

`nuxeo-labs-signature-tab.html`:

- Remove all `iron-ajax` calls.
- Add `<nuxeo-operation id="signOp" op="Signature.SignDocument">`.
- Read signing state from the document JSON via the `signatureStatus` enricher (request `enrichers-document=signatureStatus`).
- Root cert download: `<nuxeo-operation>` with `.execute()` triggering download, or simple `<a href>` to a `DownloadService` URL.

`nuxeo-labs-signature-certificate.html`:

- Replace cert-check `iron-ajax` with the `hasCertificate` enricher on the current user.
- `<nuxeo-operation op="Signature.GenerateCertificate">` for generation.
- `<nuxeo-operation op="Signature.DeleteCertificate">` for deletion.

Enricher headers:

- Ensure `enrichers-document=signatureStatus` and `enrichers-user=hasCertificate` are sent on the relevant requests (Web UI typically configures these via `nuxeo-connection`).

#### Tests (new test module)

Set up `nuxeo-labs-signature-webui-core/src/test/java/...` from scratch.

Test-scope POM dependencies: `nuxeo-runtime-test`, `nuxeo-core-test`, `nuxeo-platform-test`, `nuxeo-automation-test`, `nuxeo-platform-signature-core`, JUnit 4.

Test boilerplate:

```java
@RunWith(FeaturesRunner.class)
@Features({ AutomationFeature.class, PlatformFeature.class })
@Deploy("nuxeo.labs.signature.webui.nuxeo-labs-signature-webui-core")
@Deploy("org.nuxeo.ecm.platform.signature.core")
```

Test classes:

1. `TestHasCertificateOp` — generate, check `true`; delete, check `false`; admin-on-behalf check.
2. `TestGenerateCertificateOp` — happy path; password mismatch; password too short; password equals login (each must fail with sanitized error).
3. `TestDeleteCertificateOp` — generate then delete; delete when none exists.
4. `TestSignDocumentOp` — create a `File` with PDF blob; sign; assert main blob changed and previous archived per `disposition.pdf` setting.
5. `TestGetRootCertificateOp` — returns non-empty blob with `.crt` filename.
6. `TestSignatureStatusEnricher` — JSON serialization shape.
7. `TestHasCertificateEnricher` — JSON serialization shape.

Test fixtures:

- Sample PDF in `src/test/resources/files/sample.pdf` (can borrow from `nuxeo-signature`'s own test resources).
- Possibly `src/test/resources/OSGI-INF/test-signature-config.xml` to point to a test root keystore.

Verification:

```bash
mvn -pl nuxeo-labs-signature-webui-core test
mvn -pl nuxeo-labs-signature-webui-core -Dtest=TestSignDocumentOp test
```

#### Documentation

- `README.md` "REST API" section: replace endpoint table with operations table (id, params, return type) + a note about the two enrichers.
- `AGENTS.md`: remove WebEngine-related lines (`Nuxeo-WebModule` notes, `SignatureRoot.java` reference); add lines describing the operations + enrichers packages.

#### Suggested order

1. Add operations + enrichers + their XML — without removing `SignatureRoot.java` yet.
2. Add tests; get them green.
3. Migrate `nuxeo-labs-signature-certificate.html`; manual smoke test.
4. Migrate `nuxeo-labs-signature-tab.html`; manual smoke test.
5. Delete `SignatureRoot.java`, remove the `Nuxeo-WebModule` MANIFEST line, drop unused dependencies.
6. Update `README.md` and `AGENTS.md`.
7. Final `mvn clean install` + full manual UI test.

### Open questions before committing

1. **Test root keystore**: cert generation requires a configured root keystore. Reuse the sample bundled with `nuxeo-signature`, or ship a tiny test keystore? Need to inspect `nuxeo-signature` test resources first.
2. **Enricher cost**: `signatureStatus` could be expensive if it inspects the PDF. Must stay opt-in (only computed when explicitly requested via header) and only enabled for the Signature tab's specific request, not globally.
3. **Polymer error handling**: `<nuxeo-operation>` returns errors in a specific envelope; existing UI error-display code will need adjusting.
4. **Hybrid scope**: full migration vs. enricher-only for reads vs. only the signing call — the table above recommends the hybrid (operations + enrichers), but a narrower migration is also viable.
