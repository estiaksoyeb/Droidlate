# Droidlate Web: Unified Plural Card & Modal Editor Implementation Plan

## 1. Context & Motivation

Android string resources utilize the **Unicode CLDR (Common Locale Data Repository)** pluralization rules:

* **English (Default):** 2 forms (`one`, `other`).
* **Arabic:** 6 forms (`zero`, `one`, `two`, `few`, `many`, `other`).
* **Russian / Polish / Ukrainian:** 4 forms (`one`, `few`, `many`, `other`).
* **Japanese / Chinese / Korean:** 1 form (`other`).

### The UX Problem
Displaying each plural quantity item (`name#plural#one`, `name#plural#other`, etc.) as a separate card in the main feed duplicates the resource key 4 to 6 times, creating unnecessary clutter and confusion.

### The Unified Solution
1. In the main feed, collapse all plural items into **one unified card** displaying the resource name, forms count badge, English preview, and an **"Edit Plural Forms"** action button.
2. Tapping "Edit Plural Forms" opens a dedicated **Fullscreen / Modal Dialog** where the translator can:
   * View all base English reference forms in one overview box.
   * Translate each active quantity form (`zero`, `one`, `two`, `few`, `many`, `other`).
   * Delete redundant forms with 1 tap (e.g. Chinese/Japanese removing `one`).
   * Add missing CLDR quantities (`+ zero`, `+ two`, `+ few`, `+ many`) for languages like Arabic or Russian.

---

## 2. Backend Compatibility (Zero Backend Changes Needed)

Droidlate's existing Python engine fully supports this workflow:

1. **Saving (`POST /api/translate`):**
   * Payload: `{ "lang": "values-ar", "key": "pref_video_duration_desc#plural#zero", "value": "...", "source_hash": "..." }`
   * Automatically creates or updates `<item quantity="zero">` inside `<plurals name="pref_video_duration_desc">`.
2. **Pruning (`POST /api/prune`):**
   * Payload: `{ "lang": "values-zh", "key": "pref_video_duration_desc#plural#one" }`
   * Removes `<item quantity="one">` from the `<plurals>` block in target XML.

---

## 3. Web UI Architecture (`app.js` & `index.html`)

### A. Data Grouping in `app.js`
Transform flat string list into structured items before rendering:

```javascript
const CLDR_PLURAL_QUANTITIES = ['zero', 'one', 'two', 'few', 'many', 'other'];

function groupStringsForEditor(rawStrings) {
    const items = [];
    const pluralGroups = {};
    const seen = new Set();

    // Group plural keys
    rawStrings.forEach(entry => {
        if (entry.key.includes('#plural#')) {
            const baseKey = entry.key.split('#plural#')[0];
            if (!pluralGroups[baseKey]) pluralGroups[baseKey] = [];
            pluralGroups[baseKey].push(entry);
        }
    });

    // Build ordered list
    rawStrings.forEach(entry => {
        if (entry.key.includes('#plural#')) {
            const baseKey = entry.key.split('#plural#')[0];
            if (!seen.has('plural:' + baseKey)) {
                seen.add('plural:' + baseKey);
                const entries = pluralGroups[baseKey];
                items.push({
                    type: 'plural',
                    baseKey: baseKey,
                    entries: entries,
                    comment: entries[0]?.comment || null
                });
            }
        } else {
            if (!seen.has('single:' + entry.key)) {
                seen.add('single:' + entry.key);
                items.push({
                    type: 'single',
                    entry: entry
                });
            }
        }
    });

    return items;
}
```

---

### B. Unified Plural Group Card (Main Feed)

```javascript
function renderPluralGroupCard(group) {
    const totalCount = group.entries.length;
    const translatedCount = group.entries.filter(e => e.status === 'translated').length;
    const isAllTranslated = translatedCount === totalCount;

    const previewSources = group.entries.slice(0, 3).map(e => {
        const qty = e.key.split('#plural#')[1] || 'other';
        return `<div><span class="badge bg-secondary me-1">${qty}</span> ${escapeHtml(e.source)}</div>`;
    }).join('');

    return `
        <div class="card string-card plural-group-card mb-3">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-center mb-2">
                    <h6 class="card-title font-monospace mb-0">${escapeHtml(group.baseKey)}</h6>
                    <span class="badge bg-primary">PLURAL · ${totalCount} forms</span>
                </div>
                
                <div class="plural-source-preview p-2 bg-light rounded mb-3">
                    <div class="text-muted small fw-bold mb-1">English Source Preview:</div>
                    ${previewSources}
                    ${totalCount > 3 ? `<div class="text-muted small mt-1">+ ${totalCount - 3} more forms...</div>` : ''}
                </div>

                <div class="d-flex justify-content-between align-items-center">
                    <span class="small ${isAllTranslated ? 'text-success' : 'text-muted'} fw-semibold">
                        ${translatedCount}/${totalCount} forms translated
                    </span>
                    <button class="btn btn-sm btn-primary" onclick="openPluralEditor('${escapeHtml(group.baseKey)}')">
                        <i class="bi bi-pencil-square me-1"></i> Edit Plural Forms
                    </button>
                </div>
            </div>
        </div>
    `;
}
```

---

### C. Full-Screen Plural Modal Popup (`#pluralModal`)

#### 1. Modal HTML Structure (`index.html`):
```html
<div class="modal fade" id="pluralModal" tabindex="-1" aria-labelledby="pluralModalLabel" aria-hidden="true">
    <div class="modal-dialog modal-xl modal-dialog-scrollable">
        <div class="modal-content">
            <div class="modal-header">
                <div>
                    <h5 class="modal-title font-monospace" id="pluralModalTitle"></h5>
                    <div class="text-muted small" id="pluralModalSubtitle"></div>
                </div>
                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>
            
            <div class="modal-body" id="pluralModalBody">
                <!-- Injected dynamically via openPluralEditor() -->
            </div>
            
            <div class="modal-footer">
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Close</button>
            </div>
        </div>
    </div>
</div>
```

#### 2. Modal Controller Logic (`app.js`):
```javascript
let activePluralKey = null;

function openPluralEditor(baseKey) {
    activePluralKey = baseKey;
    const groupEntries = currentStrings.filter(s => s.key.startsWith(`${baseKey}#plural#`));
    
    document.getElementById('pluralModalTitle').innerText = baseKey;
    const translatedCount = groupEntries.filter(e => e.status === 'translated').length;
    document.getElementById('pluralModalSubtitle').innerText = `${translatedCount}/${groupEntries.length} forms translated`;

    const existingQuantities = groupEntries.map(e => e.key.split('#plural#')[1]);
    const missingQuantities = CLDR_PLURAL_QUANTITIES.filter(q => !existingQuantities.includes(q));

    let html = '';

    // 1. English Reference Card
    html += `
        <div class="card bg-light mb-3">
            <div class="card-body">
                <h6 class="card-title text-primary fw-bold mb-2">English Reference Forms</h6>
                ${groupEntries.map(e => {
                    const qty = e.key.split('#plural#')[1] || 'other';
                    return `
                        <div class="d-flex align-items-center mb-1">
                            <span class="badge bg-primary me-2 font-monospace">${qty}</span>
                            <span class="text-dark">${escapeHtml(e.source)}</span>
                        </div>
                    `;
                }).join('')}
            </div>
        </div>
    `;

    // 2. Add Missing Quantities Chips
    if (missingQuantities.length > 0) {
        html += `
            <div class="card mb-3 border-primary">
                <div class="card-body py-2">
                    <span class="small text-muted me-2">Add plural forms for this language (e.g. Arabic, Russian):</span>
                    <div class="d-inline-flex gap-1">
                        ${missingQuantities.map(qty => `
                            <button class="btn btn-sm btn-outline-primary" onclick="addPluralQuantity('${escapeHtml(baseKey)}', '${qty}')">
                                + ${qty}
                            </button>
                        `).join('')}
                    </div>
                </div>
            </div>
        `;
    }

    // 3. Target Quantity Translation Rows
    groupEntries.forEach(entry => {
        const qty = entry.key.split('#plural#')[1] || 'other';
        html += `
            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-center mb-2">
                        <div>
                            <span class="badge bg-primary-subtle text-primary font-monospace fs-6">quantity="${qty}"</span>
                            ${qty === 'other' ? '<span class="text-muted small ms-2">(Required fallback)</span>' : ''}
                        </div>
                        ${qty !== 'other' ? `
                            <button class="btn btn-sm btn-outline-danger" title="Delete this plural form" onclick="deletePluralForm('${escapeHtml(entry.key)}')">
                                <i class="bi bi-trash"></i> Delete
                            </button>
                        ` : ''}
                    </div>

                    <div class="mb-2">
                        <input type="text" class="form-control" id="input-${escapeHtml(entry.key)}" 
                               value="${escapeHtml(entry.translation)}" 
                               placeholder="Translation for '${qty}'...">
                    </div>

                    <div class="d-flex justify-content-end">
                        <button class="btn btn-sm btn-success" onclick="savePluralForm('${escapeHtml(entry.key)}')">
                            <i class="bi bi-check-lg me-1"></i> Save
                        </button>
                    </div>
                </div>
            </div>
        `;
    });

    document.getElementById('pluralModalBody').innerHTML = html;

    const modal = new bootstrap.Modal(document.getElementById('pluralModal'));
    modal.show();
}

async function savePluralForm(key) {
    const input = document.getElementById(`input-${key}`);
    if (!input) return;
    const value = input.value;
    const entry = currentStrings.find(s => s.key === key);

    const response = await fetch('/api/translate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            lang: currentLang,
            key: key,
            value: value,
            source_hash: entry?.source_hash || ''
        })
    });

    if (response.ok) {
        if (entry) {
            entry.translation = value;
            entry.status = value ? 'translated' : 'untranslated';
        }
        renderStrings();
        openPluralEditor(activePluralKey);
    }
}

async function deletePluralForm(key) {
    if (!confirm(`Remove plural form "${key}" from this language?`)) return;

    const response = await fetch('/api/prune', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lang: currentLang, key: key })
    });

    if (response.ok) {
        currentStrings = currentStrings.filter(s => s.key !== key);
        renderStrings();
        openPluralEditor(activePluralKey);
    }
}

function addPluralQuantity(baseKey, quantity) {
    const firstRef = currentStrings.find(s => s.key.startsWith(`${baseKey}#plural#`));
    const newKey = `${baseKey}#plural#${quantity}`;
    
    currentStrings.push({
        key: newKey,
        source: firstRef?.source || '',
        source_hash: firstRef?.source_hash || '',
        translation: '',
        status: 'untranslated'
    });

    renderStrings();
    openPluralEditor(baseKey);
}
```

---

## 4. Benefits of this Architecture

1. **Zero Clutter in Main Feed:** Every `<plurals>` resource occupies exactly one row in the list.
2. **Unified Context:** Translators see all source and target quantities in one modal side-by-side.
3. **True CLDR Multi-Language Support:**
   * ➕ **Arabic / Slavic:** Add missing forms (`zero`, `two`, `few`, `many`) with 1 click.
   * 🗑️ **CJK (Chinese, Japanese, Korean):** Delete redundant forms (`one`) keeping only `other`.
4. **Build Safety:** `quantity="other"` is locked as the non-deletable fallback required by Android AAPT2.
