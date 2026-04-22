# Ideas for Future Enhancements

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
