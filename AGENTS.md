# Nuxeo Labs Signature for Web UI — Agent Guide

## Project

Nuxeo LTS 2025 plugin (Java 21, Maven) that adds Web UI support (REST endpoints + Polymer web components) for the existing `nuxeo-signature` Digital Signature addon. Does NOT modify any original server-side code.

- **Parent**: `org.nuxeo:nuxeo-parent:2025.16`
- **GroupId**: `nuxeo.labs.signature.webui`
- **Version**: `2025.1.0-SNAPSHOT`

## Modules

| Module | Purpose |
|--------|---------|
| `nuxeo-labs-signature-webui-core` | Java REST endpoint, OSGI-INF components, Web UI elements, i18n |
| `nuxeo-labs-signature-webui-package` | Nuxeo Marketplace package (assembly, install, package dependencies) |

Key paths in the core module:

- Java sources: `nuxeo-labs-signature-webui-core/src/main/java/nuxeo/labs/signature/webui/`
- Component XMLs: `nuxeo-labs-signature-webui-core/src/main/resources/OSGI-INF/`
- Bundle manifest: `nuxeo-labs-signature-webui-core/src/main/resources/META-INF/MANIFEST.MF`
- Web UI elements: `nuxeo-labs-signature-webui-core/src/main/resources/web/nuxeo.war/ui/nuxeo-labs-signature/`
- i18n: `nuxeo-labs-signature-webui-core/src/main/resources/web/nuxeo.war/ui/i18n/`

## Build & Test Commands

```bash
# Full build
mvn clean install

# Build skipping tests
mvn clean install -DskipTests
```

No tests exist yet. No CI, no linter, no formatter configured in this repo.

## Current Code

- **`SignatureRoot.java`** — WebEngine REST endpoint at `/api/v1/signature/` exposing certificate management (GET/POST/DELETE `/certificate`, GET `/certificate/{username}` admin-only, GET `/rootcertificate`) and document signing (GET/POST `/document/{docId}`). Calls existing `SignatureService`, `CUserService`, `CertService` via `Framework.getService(...)`.
- **`signature-webui-contrib.xml`** — Nuxeo component that registers the Web UI HTML entry point via the `WebResources` extension point (resource registration + append to `web-ui` bundle).
- **`deployment-fragment.xml`** — Extracts web resources from the bundle JAR and **appends** i18n translations (does NOT overwrite).
- **`nuxeo-labs-signature-contrib.html`** — Slot contributions: `DOCUMENT_VIEWS_ITEMS`/`DOCUMENT_VIEWS_PAGES` for the Signature tab, `USER_MENU`/`PAGES` for the Certificate management page.
- **`nuxeo-labs-signature-tab.html`** — Polymer element for the document Signature tab (signing status, signature list, signing form, root cert download).
- **`nuxeo-labs-signature-certificate.html`** — Polymer element for certificate management (generate, view, delete) in the user menu.
- **`messages.json`** / **`messages-fr.json`** — English and French translations.

## Plugin-Specific Constraints

- **All dependencies must be `<scope>provided</scope>`** — everything is already deployed by the base Nuxeo server or the `nuxeo-signature` package. This keeps the marketplace package small (~19KB).
- **Never bundle crypto/signature JARs** (openpdf, bcprov, bcpkix) — they are provided by the `nuxeo-signature` package.
- **Web UI HTML files require explicit registration** via the `WebResources` extension point (there is no auto-discovery). See `signature-webui-contrib.xml`.
- **i18n files must be appended**, not overwritten — see the `<append>` directives in `deployment-fragment.xml`.
- **WebEngine registration**: The `Nuxeo-WebModule` MANIFEST header must reference `org.nuxeo.ecm.webengine.app.WebEngineModule` (the generic base class) with a `package=` attribute pointing to the Java package to scan. Do NOT reference the `ModuleRoot` class directly.

## Adding New Code

### New REST Endpoint Method

1. Add the method to `SignatureRoot.java` with JAX-RS annotations (`@GET`/`@POST`/`@Path`/etc.)
2. Use `Framework.getService(...)` to access existing Nuxeo services
3. Return JSON via the `jsonResponse()` helper method
4. Sanitize error messages — never expose `e.getMessage()` to clients; log at `warn` level instead

### New Web UI Element

1. Create the HTML file in `web/nuxeo.war/ui/nuxeo-labs-signature/` with prefix `nuxeo-labs-signature-`
2. Add `<link rel="import">` in `nuxeo-labs-signature-contrib.html`
3. Register slot contributions in the contrib file if needed
4. Add translation keys to both `messages.json` and `messages-fr.json`

### Dependencies

Module POMs declare dependencies **without `<version>` tags** — versions are managed by `nuxeo-parent`. All dependencies in the core module must be `<scope>provided</scope>`.

## Critical Pitfalls

These will cause silent failures or build errors if ignored:

- **NOT Spring**: No `@Autowired`, `@Component`, `@Service`. Use Nuxeo's component model (`DefaultComponent`, `Framework.getService()`)
- **Jakarta, not javax**: All imports use `jakarta.*` namespace
- **JUnit 4 only**: Use `@RunWith(FeaturesRunner.class)` + `@Features(...)` + `@Deploy(...)`. No JUnit 5
- **Log4j2 only**: `LogManager.getLogger(MyClass.class)`. No SLF4J, no `System.out.println`
- **No raw Jackson for REST**: Use Nuxeo's `MarshallerRegistry` framework
- **No JPA**: Document storage uses `CoreSession` / `DocumentModel` API
- **WebEngine module class**: `Nuxeo-WebModule` must reference `WebEngineModule`, not a `ModuleRoot` subclass
- **i18n**: Always append translations via `deployment-fragment.xml`, never overwrite

## Testing Patterns

```java
@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("nuxeo.labs.signature.webui.nuxeo-labs-signature-webui-core")
public class TestSignatureRoot {

    @Inject
    protected CoreSession session;

    @Test
    public void testSomething() throws Exception {
        // ...
    }
}
```

- `@Deploy("bundle.symbolic.name")` — the symbolic name is in MANIFEST.MF (`Bundle-SymbolicName`)
- `@Deploy("bundle:OSGI-INF/test-contrib.xml")` — deploy test-only XML contributions
- `TransactionalFeature.nextTransaction()` — flush async work between steps

## Local References (optional)

If the Nuxeo LTS 2025 source code or other plugin examples are available locally, working with local files is faster and avoids network calls. Ask the user for local paths before falling back to GitHub.

Prompt the user with:

> "Do you have the Nuxeo LTS 2025 source code cloned locally? If so, what is the path? (e.g., `~/GitHub/nuxeo/nuxeo-lts`). Otherwise, I'll use the GitHub repository."

Similarly, for plugin examples:

> "Do you have any Nuxeo plugin examples locally? (e.g., `nuxeo-labs-dynamic-fields`, `nuxeo-dynamic-asset-transformation`). If so, what are the paths? Otherwise, I'll browse them on GitHub."

### Fallback URLs

- Nuxeo LTS 2025: https://github.com/nuxeo/nuxeo-lts (branch `2025`)
- `nuxeo-signature` source: `modules/platform/nuxeo-signature/` within the above
- Plugin example: https://github.com/nuxeo-sandbox/nuxeo-labs-dynamic-fields

## Code Style

### Java

- 4-space indent, **no tabs**, no trailing spaces, K&R braces, ~120 char lines
- Use modern Java: `var`, records, pattern matching `instanceof`, switch expressions, text blocks, `String.formatted()`
- **No wildcard imports**. Import order: static, `java.*`, `jakarta.*`, `org.*`, `com.*`
- Always use braces for `if`/`else` blocks (even single-line)
- Logging: parameterized messages `log.debug("Processing: {}", docId)`
- Javadoc: `@since 2025.16` on new public API. No `@author` tag
- License header: Apache 2.0 with current year and `Contributors:` section

### XML (OSGI-INF, POMs, XSD, HTML)

- **2-space indent**, no tabs, 120 char line width
- Self-closing tags: add space before `/>` (e.g., `<property name="foo" />`)

### Markdown (README, docs)

- Use GitHub alert syntax for notes, warnings, tips, etc.:
  ```
  > [!NOTE]
  > Content here

  > [!TIP]
  > Content here

  > [!IMPORTANT]
  > Content here

  > [!WARNING]
  > Content here

  > [!CAUTION]
  > Content here
  ```
- See [GitHub alerts documentation](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax#alerts)

## Release Process

> [!WARNING]
> This is a project-specific release workflow. The version numbers below are examples — derive actual values from the current POM version.

### Steps

1. **Check the repository is clean**
   - Run `git status` and verify there are no uncommitted changes, staged files, or pending pulls/pushes.
   - If anything is outstanding, alert the user and **stop** — do not proceed with the release.

2. **Set the release version**
   - Remove `-SNAPSHOT` from the current version (e.g., `2025.1.0-SNAPSHOT` → `2025.1.0`).
   - `mvn versions:set -DnewVersion=2025.1.0 -DgenerateBackupPoms=false`

3. **Build the release**
   - `mvn clean install -DskipTests`

4. **Copy the marketplace package to `~/Downloads`**
   - Copy `{plugin}-package/target/{plugin}-package-{version}.zip` to `~/Downloads/`.

5. **Set the next development version**
   - Increment the minor version, reset incremental to zero, add `-SNAPSHOT` (e.g., `2025.1.0` → `2025.2.0-SNAPSHOT`).
   - `mvn versions:set -DnewVersion=2025.2.0-SNAPSHOT -DgenerateBackupPoms=false`

6. **Verify the build**
   - `mvn clean install -DskipTests`

7. **Commit and push**
   ```bash
   git add .
   git commit -m "Post 2025.1.0 release"
   git push
   ```

> [!NOTE]
> No git tag is created. No GitHub release is created. The release version is intentionally not committed — only the post-release snapshot version is committed and pushed. The marketplace package ZIP copied to `~/Downloads` is the deliverable.
