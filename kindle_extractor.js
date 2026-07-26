/**
 * Wombat-Liberates: Kindle Cloud Reader Text Extractor
 * Open read.amazon.com, open your book, press F12 (Developer Tools) -> Console tab,
 * paste this script and press Enter.
 */
(function extractKindleText() {
    let lines = [];

    function searchDocument(doc) {
        // Query paragraph elements, KFX rendering spans, and accessibility text nodes
        let nodes = doc.querySelectorAll('p, .kfx-text-line, [role="text"], .kfx-symbol');
        if (nodes.length === 0) {
            nodes = doc.querySelectorAll('span, div');
        }
        nodes.forEach(node => {
            let txt = node.innerText || node.textContent || '';
            txt = txt.trim();
            // Filter out empty lines, UI buttons, and short navigation labels
            if (txt.length > 0 && 
                !txt.includes("Table of Contents") && 
                !txt.includes("Location ") && 
                !txt.includes("Page ")
            ) {
                lines.push(txt);
            }
        });
    }

    // Search main document
    searchDocument(document);

    // Search inside all iframes (Kindle Cloud Reader renders book pages in an iframe)
    document.querySelectorAll('iframe').forEach(iframe => {
        try {
            if (iframe.contentDocument) {
                searchDocument(iframe.contentDocument);
            }
        } catch (e) {
            console.warn("Could not access iframe:", e);
        }
    });

    // Deduplicate consecutive identical lines
    let cleanLines = [];
    lines.forEach((line, index) => {
        if (index === 0 || line !== lines[index - 1]) {
            cleanLines.push(line);
        }
    });

    let resultText = cleanLines.join('\n\n');

    console.clear();
    console.log("%c=== WOMBAT-LIBERATES: EXTRACTED KINDLE TEXT ===", "color: #00ff00; font-size: 16px; font-weight: bold;");
    console.log(resultText);
    console.log("%c=================================================", "color: #00ff00; font-size: 16px; font-weight: bold;");

    // Copy result to clipboard automatically if supported
    if (navigator.clipboard) {
        navigator.clipboard.writeText(resultText).then(() => {
            console.log("%c[SUCCESS] Text copied to clipboard!", "color: #00ccff; font-weight: bold;");
        });
    }

    return resultText;
})();
