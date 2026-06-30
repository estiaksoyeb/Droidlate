// Application State
let state = {
    project: null,        // Mode, res_dir, source_file, languages
    activeLang: null,      // Target language folder (e.g. values-es)
    strings: [],          // List of strings in active language
    currentKey: null,      // Active editing key name
    activeFilter: 'all',  // Filters: all, untranslated, outdated, warnings
    searchQuery: '',      // Search field input string
    filteredStrings: []   // Filtered strings list
};

// UI Elements
const el = {
    projectPath: document.getElementById('project-path-display'),
    dashboardView: document.getElementById('dashboard-view'),
    editorView: document.getElementById('editor-view'),
    languagesGrid: document.getElementById('languages-grid'),
    btnBackDashboard: document.getElementById('btn-back-dashboard'),
    btnBackSidebar: document.getElementById('btn-back-sidebar'),
    editorLayoutContainer: document.getElementById('editor-layout-container'),
    keySearch: document.getElementById('key-search'),
    keysList: document.getElementById('keys-list'),
    currentKeyName: document.getElementById('current-key-name'),
    currentKeyStatus: document.getElementById('current-key-status'),
    currentKeyComment: document.getElementById('current-key-comment'),
    currentKeyAttribs: document.getElementById('current-key-attribs'),
    btnCopySource: document.getElementById('btn-copy-source'),
    sourceTextDisplay: document.getElementById('source-text-display'),
    translationInput: document.getElementById('translation-input'),
    validationPanel: document.getElementById('validation-panel'),
    validationIcon: document.getElementById('validation-icon'),
    validationSummary: document.getElementById('validation-summary'),
    validationErrorsList: document.getElementById('validation-errors-list'),
    suggestionsList: document.getElementById('suggestions-list'),
    btnSkipKey: document.getElementById('btn-skip-key'),
    btnSaveKey: document.getElementById('btn-save-key'),
    btnPruneKey: document.getElementById('btn-prune-key'),
    statusBarLeft: document.getElementById('status-left'),
    statusBarProgressFill: document.getElementById('status-progress-fill'),
    statusBarProgressLabel: document.getElementById('status-progress-label'),
    statusBarRight: document.getElementById('status-right'),
    filterTabs: document.querySelectorAll('.filter-tab')
};

// Setup Event Listeners
function setupEventListeners() {
    // Return to dashboard
    el.btnBackDashboard.addEventListener('click', showDashboard);

    // Return to sidebar keys list on mobile
    el.btnBackSidebar.addEventListener('click', () => {
        el.editorLayoutContainer.classList.remove('show-workspace');
        el.editorLayoutContainer.classList.add('show-sidebar');
    });

    // Search and Filter updates
    el.keySearch.addEventListener('input', (e) => {
        state.searchQuery = e.target.value.toLowerCase();
        applySidebarFilters();
    });

    el.filterTabs.forEach(tab => {
        tab.addEventListener('click', (e) => {
            el.filterTabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            state.activeFilter = tab.dataset.filter;
            applySidebarFilters();
        });
    });

    // Copy source helper
    el.btnCopySource.addEventListener('click', () => {
        navigator.clipboard.writeText(el.sourceTextDisplay.textContent)
            .then(() => {
                const oldText = el.btnCopySource.textContent;
                el.btnCopySource.textContent = "Copied!";
                setTimeout(() => el.btnCopySource.textContent = oldText, 1500);
            });
    });

    // Save & Skip triggers
    el.btnSaveKey.addEventListener('click', saveCurrentTranslation);
    el.btnPruneKey.addEventListener('click', pruneCurrentTranslation);
    el.btnSkipKey.addEventListener('click', navigateNextAttention);

    // Live validation check on typing
    el.translationInput.addEventListener('input', (e) => {
        const activeStr = state.strings.find(s => s.key === state.currentKey);
        if (activeStr) {
            const warnings = runValidation(activeStr.source, e.target.value);
            updateValidationUI(warnings);
        }
    });

    // Keyboard Shortcuts listener
    document.addEventListener('keydown', (e) => {
        // Ctrl+S to save
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
            e.preventDefault();
            if (state.activeLang && state.currentKey) {
                saveCurrentTranslation();
            }
        }
        
        // Escape to exit editor / go back to sidebar on mobile
        if (e.key === 'Escape' && state.activeLang) {
            e.preventDefault();
            if (window.innerWidth < 768 && el.editorLayoutContainer.classList.contains('show-workspace')) {
                el.editorLayoutContainer.classList.remove('show-workspace');
                el.editorLayoutContainer.classList.add('show-sidebar');
            } else {
                showDashboard();
            }
        }

        // Alt+1, Alt+2, Alt+3 for suggestions pasting
        if (e.altKey && ['1', '2', '3'].includes(e.key)) {
            e.preventDefault();
            const cards = el.suggestionsList.querySelectorAll('.suggestion-card');
            const idx = parseInt(e.key) - 1;
            if (cards[idx] && !cards[idx].classList.contains('loading')) {
                const text = cards[idx].querySelector('.suggestion-text').textContent;
                el.translationInput.value = text;
                el.translationInput.focus();
                // Trigger live validation update
                const activeStr = state.strings.find(s => s.key === state.currentKey);
                if (activeStr) {
                    const warnings = runValidation(activeStr.source, text);
                    updateValidationUI(warnings);
                }
            }
        }
    });
}

// 1. View Routing & Dashboard loading
function showDashboard() {
    state.activeLang = null;
    state.strings = [];
    state.currentKey = null;
    
    // Toggle active view CSS
    el.dashboardView.classList.add('active');
    el.editorView.classList.remove('active');
    
    // Clear list items
    el.languagesGrid.innerHTML = `
        <div class="loader-container">
            <div class="loader"></div>
            <p>Scanning resource directory...</p>
        </div>
    `;

    fetch('/api/project')
        .then(res => res.json())
        .then(data => {
            state.project = data;
            
            // Render Path
            if (data.mode === 'single') {
                el.projectPath.textContent = `Single File Mode: ${data.target_file}`;
                el.statusBarLeft.textContent = "Running in single file mode";
                el.statusBarRight.textContent = "Locales: 1";
            } else {
                el.projectPath.textContent = `Directory: ${data.res_dir}`;
                el.statusBarLeft.textContent = `Scanned resource directory successfully`;
                el.statusBarRight.textContent = `Locales: ${data.languages.length}`;
            }

            renderDashboardCards(data.languages);
            updateOverallProgress(data.languages);
        })
        .catch(err => {
            el.languagesGrid.innerHTML = `<div class="loader-container"><p class="stat-untranslated">Failed to load project details.</p></div>`;
        });
}

function renderDashboardCards(languages) {
    el.languagesGrid.innerHTML = '';
    
    if (languages.length === 0) {
        el.languagesGrid.innerHTML = `<div class="loader-container"><p>No language locales found.</p></div>`;
        return;
    }

    languages.forEach(lang => {
        const card = document.createElement('div');
        card.className = 'lang-card';
        
        // Progress SVG Calculations
        const radius = 30;
        const circumference = 2 * Math.PI * radius;
        const offset = circumference - (lang.progress / 100) * circumference;

        card.innerHTML = `
            <div class="lang-card-header">
                <h3>${lang.folder}</h3>
                <span class="lang-badge">${lang.locale}</span>
            </div>
            <div class="lang-card-content">
                <div class="progress-circle-container">
                    <svg class="progress-circle-svg">
                        <circle class="progress-circle-bg" cx="35" cy="35" r="${radius}"></circle>
                        <circle class="progress-circle-fill" cx="35" cy="35" r="${radius}" 
                                stroke-dasharray="${circumference}" 
                                stroke-dashoffset="${offset}"></circle>
                    </svg>
                    <span class="progress-value-text">${lang.progress}%</span>
                </div>
                <div class="lang-stats">
                    <div class="stat-item">Translated: <span class="stat-val">${lang.translated}</span></div>
                    <div class="stat-item">Outdated: <span class="stat-val stat-outdated">${lang.outdated}</span></div>
                    <div class="stat-item">Untranslated: <span class="stat-val stat-untranslated">${lang.untranslated}</span></div>
                    <div class="stat-item">Orphaned: <span class="stat-val stat-untranslated" style="color:var(--danger);">${lang.orphaned || 0}</span></div>
                    <div class="stat-item">Total: <span class="stat-val">${lang.total}</span></div>
                </div>
            </div>
        `;

        card.addEventListener('click', () => showEditor(lang.folder));
        el.languagesGrid.appendChild(card);
    });
}

function updateOverallProgress(languages) {
    if (languages.length === 0) return;
    const avgProgress = Math.round(languages.reduce((acc, curr) => acc + curr.progress, 0) / languages.length);
    el.statusBarProgressFill.style.width = `${avgProgress}%`;
    el.statusBarProgressLabel.textContent = `${avgProgress}% Done`;
}

// 2. Editor Workspace actions
function showEditor(langFolder) {
    state.activeLang = langFolder;
    
    // Toggle active view CSS
    el.dashboardView.classList.remove('active');
    el.editorView.classList.add('active');
    
    el.keysList.innerHTML = '<div class="loader-container"><div class="loader"></div></div>';
    
    fetch(`/api/strings?lang=${encodeURIComponent(langFolder)}`)
        .then(res => res.json())
        .then(data => {
            state.strings = data.strings;

            // Default filter to untranslated, but fallback if empty
            const untranslatedCount = state.strings.filter(s => s.status === 'untranslated').length;
            const outdatedCount = state.strings.filter(s => s.status === 'outdated' || s.status === 'warnings').length;
            
            if (untranslatedCount > 0) {
                state.activeFilter = 'untranslated';
            } else if (outdatedCount > 0) {
                state.activeFilter = 'outdated';
            } else {
                state.activeFilter = 'all';
            }
            
            // Sync filter tabs classes in UI
            el.filterTabs.forEach(tab => {
                if (tab.dataset.filter === state.activeFilter) {
                    tab.classList.add('active');
                } else {
                    tab.classList.remove('active');
                }
            });

            applySidebarFilters();
            
            // Select first key that matches the filter
            if (state.filteredStrings.length > 0) {
                selectKey(state.filteredStrings[0].key);
            }
        })
        .catch(err => {
            el.keysList.innerHTML = '<li class="key-list-item"><span class="key-text stat-untranslated">Failed to load strings list.</span></li>';
        });
}

function applySidebarFilters() {
    el.keysList.innerHTML = '';
    
    state.filteredStrings = state.strings.filter(item => {
        const keyMatch = item.key.toLowerCase().includes(state.searchQuery) ||
                         item.source.toLowerCase().includes(state.searchQuery) ||
                         item.translation.toLowerCase().includes(state.searchQuery);
                         
        if (!keyMatch) return false;
        
        if (state.activeFilter === 'all') return true;
        return item.status === state.activeFilter;
    });

    if (state.filteredStrings.length === 0) {
        el.keysList.innerHTML = '<li class="key-list-item dimmed" style="cursor:default;"><span class="key-text">No matches found.</span></li>';
        return;
    }

    state.filteredStrings.forEach(item => {
        const li = document.createElement('li');
        li.className = `key-list-item ${item.key === state.currentKey ? 'active' : ''}`;
        li.id = `sidebar-key-${item.key}`;
        
        // Status indicator badge
        const badgeChar = item.status === 'untranslated' ? 'U' :
                          item.status === 'outdated' ? 'O' :
                          item.status === 'warnings' ? 'W' :
                          item.status === 'orphaned' ? 'Ø' : 'T';
                          
        li.innerHTML = `
            <span class="key-text">${item.key}</span>
            <span class="key-status-dot badge-${badgeChar}">${badgeChar}</span>
        `;
        
        li.addEventListener('click', () => selectKey(item.key));
        el.keysList.appendChild(li);
    });

    // Update bottom editor sidebar stats
    updateEditorStats();
}

function updateEditorStats() {
    const total = state.strings.length;
    const untranslated = state.strings.filter(s => s.status === 'untranslated').length;
    const outdated = state.strings.filter(s => s.status === 'outdated' || s.status === 'warnings').length;
    const translated = total - untranslated - outdated;
    
    const progress = Math.round((translated / total) * 100);
    
    el.statusBarLeft.textContent = `Editing ${state.activeLang} | Untranslated: ${untranslated} | Outdated: ${outdated}`;
    el.statusBarProgressFill.style.width = `${progress}%`;
    el.statusBarProgressLabel.textContent = `${progress}% Done`;
}

function selectKey(key) {
    state.currentKey = key;
    
    // Toggle active classes for mobile view
    el.editorLayoutContainer.classList.remove('show-sidebar');
    el.editorLayoutContainer.classList.add('show-workspace');
    
    // Highlight active list item
    const items = el.keysList.querySelectorAll('.key-list-item');
    items.forEach(item => item.classList.remove('active'));
    
    const activeItem = document.getElementById(`sidebar-key-${key}`);
    if (activeItem) {
        activeItem.classList.add('active');
        activeItem.scrollIntoView({ block: 'nearest' });
    }

    const item = state.strings.find(s => s.key === key);
    if (!item) return;

    // Render metadata card
    el.currentKeyName.textContent = `string:${item.key}`;
    el.currentKeyStatus.textContent = item.status.toUpperCase();
    el.currentKeyStatus.className = `status-badge ${item.status.toUpperCase()}`;
    
    if (item.comment) {
        el.currentKeyComment.textContent = item.comment;
        el.currentKeyComment.classList.remove('dimmed');
    } else {
        el.currentKeyComment.textContent = "No comment provided.";
        el.currentKeyComment.classList.add('dimmed');
    }

    // Attributes list
    const attrKeys = Object.keys(item.attrib || {});
    if (attrKeys.length > 0) {
        el.currentKeyAttribs.textContent = attrKeys.map(k => `${k}="${item.attrib[k]}"`).join(', ');
    } else {
        el.currentKeyAttribs.textContent = "None";
    }

    // Render panels
    el.sourceTextDisplay.textContent = item.source;
    el.translationInput.value = item.translation;
    
    if (item.status === 'orphaned') {
        el.btnSaveKey.style.display = 'none';
        el.btnPruneKey.style.display = 'inline-block';
        el.translationInput.readOnly = true;
        el.btnCopySource.style.display = 'none';
        el.suggestionsList.innerHTML = '<div class="suggestion-card loading">Suggestions not available for orphaned keys</div>';
        
        // Custom warning message in validation panel
        updateValidationUI(["This string has been removed from the English source XML file. You can safely prune it to keep your resources clean."]);
        el.translationInput.focus();
    } else {
        el.btnSaveKey.style.display = 'inline-block';
        el.btnPruneKey.style.display = 'none';
        el.translationInput.readOnly = false;
        el.btnCopySource.style.display = 'inline-block';
        el.translationInput.focus();
        
        // Clean suggestions panel
        el.suggestionsList.innerHTML = '<div class="suggestion-card loading">Fetching suggestions...</div>';

        // Live validation check on focus load
        const warnings = runValidation(item.source, item.translation);
        updateValidationUI(warnings);

        // Asynchronously query suggestions
        fetchSuggestions(key, item.source);
    }
}

// 3. Auto Suggestions fetching
function fetchSuggestions(key, text) {
    const srcLocale = 'values';
    const tgtLocale = state.activeLang;
    
    fetch(`/api/suggest?text=${encodeURIComponent(text)}&src=${srcLocale}&tgt=${tgtLocale}`)
        .then(res => res.json())
        .then(data => {
            // Suggestion Race Guard: Discard callback if user has already navigated away from the key
            if (state.currentKey !== key) {
                return;
            }

            renderSuggestions(data.suggestions);
        })
        .catch(err => {
            if (state.currentKey === key) {
                el.suggestionsList.innerHTML = '<div class="suggestion-card loading">Suggestions unavailable</div>';
            }
        });
}

function renderSuggestions(suggestions) {
    el.suggestionsList.innerHTML = '';
    
    if (!suggestions || suggestions.length === 0) {
        el.suggestionsList.innerHTML = '<div class="suggestion-card loading">No suggestions available.</div>';
        return;
    }

    suggestions.forEach((sug, idx) => {
        const shortcut = idx + 1;
        const card = document.createElement('div');
        card.className = 'suggestion-card';
        card.innerHTML = `
            <div class="suggestion-provider">${sug.provider} (Alt+${shortcut})</div>
            <div class="suggestion-text">${sug.text}</div>
        `;
        
        card.addEventListener('click', () => {
            el.translationInput.value = sug.text;
            el.translationInput.focus();
            const activeStr = state.strings.find(s => s.key === state.currentKey);
            if (activeStr) {
                const warnings = runValidation(activeStr.source, sug.text);
                updateValidationUI(warnings);
            }
        });
        
        el.suggestionsList.appendChild(card);
    });
}

// 4. Live Placeholders Validation (Java format regex)
function runValidation(source, translation) {
    if (!translation) return [];
    
    const PLACEHOLDER_REGEX = /%([0-9]+\$)?[-#+ 0,\(<]*[0-9]*(\.[0-9]+)?([a-zA-Z%])/g;
    
    const extract = (str) => {
        let matches = [];
        let m;
        // Reset match index
        PLACEHOLDER_REGEX.lastIndex = 0;
        while ((m = PLACEHOLDER_REGEX.exec(str)) !== null) {
            const idxStr = m[1];
            const ptype = m[3];
            if (ptype === '%') continue; // Skip literal %%
            
            const idx = idxStr ? parseInt(idxStr.slice(0, -1)) : null;
            matches.push({ index: idx, type: ptype });
        }
        return matches;
    };

    const resolve = (list) => {
        let implicitIdx = 1;
        return list.map(item => {
            if (item.index === null) {
                const res = { index: implicitIdx, type: item.type };
                implicitIdx++;
                return res;
            }
            return item;
        });
    };

    const srcList = resolve(extract(source));
    const tgtList = resolve(extract(translation));

    const srcMap = {};
    srcList.forEach(item => srcMap[item.index] = item.type);
    
    const tgtMap = {};
    tgtList.forEach(item => tgtMap[item.index] = item.type);

    const warnings = [];

    // Check for missing or type mismatch
    Object.keys(srcMap).forEach(idx => {
        const srcType = srcMap[idx];
        if (!(idx in tgtMap)) {
            warnings.push(`Missing placeholder %${idx}$${srcType}`);
        } else if (tgtMap[idx] !== srcType) {
            warnings.push(`Type mismatch for placeholder %${idx}: expected type '${srcType}', got '${tgtMap[idx]}'`);
        }
    });

    // Check for unexpected extra placeholders
    Object.keys(tgtMap).forEach(idx => {
        if (!(idx in srcMap)) {
            warnings.push(`Extra/unexpected placeholder %${idx}$${tgtMap[idx]}`);
        }
    });

    return warnings;
}

function updateValidationUI(warnings) {
    el.validationErrorsList.innerHTML = '';
    
    if (warnings.length === 0) {
        el.validationPanel.classList.remove('invalid');
        el.validationIcon.textContent = '✓';
        el.validationSummary.textContent = 'Placeholders match source';
    } else {
        el.validationPanel.classList.add('invalid');
        el.validationIcon.textContent = '✗';
        el.validationSummary.textContent = `${warnings.length} Placeholder warning${warnings.length > 1 ? 's' : ''} found:`;
        
        warnings.forEach(warn => {
            const li = document.createElement('li');
            li.textContent = warn;
            el.validationErrorsList.appendChild(li);
        });
    }
}

// 5. Save translation api POST with stale protection
function saveCurrentTranslation() {
    const value = el.translationInput.value.trim();
    const activeStr = state.strings.find(s => s.key === state.currentKey);
    
    if (!activeStr) return;

    // Check formatting warnings
    const warnings = runValidation(activeStr.source, value);
    if (warnings.length > 0) {
        const confirmSave = confirm(
            `Placeholder warnings found:\n\n${warnings.map(w => '• ' + w).join('\n')}\n\nDo you want to save anyway?`
        );
        if (!confirmSave) return;
    }

    el.btnSaveKey.disabled = true;
    el.btnSaveKey.textContent = "Saving...";

    fetch('/api/translate', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            lang: state.activeLang,
            key: state.currentKey,
            value: value,
            source_hash: activeStr.source_hash // Stale-state protection check
        })
    })
    .then(res => {
        if (res.status === 409) {
            // Stale source detection error!
            return res.json().then(data => {
                throw new Error(data.message || "The source string has been modified by another process. Please reload.");
            });
        }
        return res.json();
    })
    .then(data => {
        el.btnSaveKey.disabled = false;
        el.btnSaveKey.textContent = "Save & Next";
        
        if (data.success) {
            // Update local status representation
            activeStr.translation = value;
            activeStr.status = warnings.length > 0 ? 'warnings' : 'translated';
            
            // Reapply filters to update sidebar list elements
            applySidebarFilters();
            
            // Advance to next key requiring attention
            navigateNextAttention();
        } else {
            alert("Error writing translation to disk.");
        }
    })
    .catch(err => {
        el.btnSaveKey.disabled = false;
        el.btnSaveKey.textContent = "Save & Next";
        alert(`Save Blocked: ${err.message}`);
    });
}

function navigateNextAttention() {
    if (state.filteredStrings.length === 0) return;
    
    const currIdx = state.filteredStrings.findIndex(s => s.key === state.currentKey);
    const n = state.filteredStrings.length;
    
    // Search forward for next todo
    for (let i = 1; i <= n; i++) {
        const idx = (currIdx + i) % n;
        const item = state.filteredStrings[idx];
        if (item.status !== 'translated') {
            selectKey(item.key);
            return;
        }
    }
    
    // Default to the next chronological key if all are translated
    if (currIdx !== -1) {
        const nextItem = state.filteredStrings[(currIdx + 1) % n];
        selectKey(nextItem.key);
    }
}

function pruneCurrentTranslation() {
    if (!confirm(`Are you sure you want to permanently prune the orphaned translation for key "${state.currentKey}"?`)) {
        return;
    }
    
    el.btnPruneKey.disabled = true;
    el.btnPruneKey.textContent = "Pruning...";
    
    fetch('/api/prune', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            lang: state.activeLang,
            key: state.currentKey
        })
    })
    .then(res => res.json())
    .then(data => {
        el.btnPruneKey.disabled = false;
        el.btnPruneKey.textContent = "Prune Translation";
        
        if (data.success) {
            // Remove key locally from the state.strings list
            state.strings = state.strings.filter(s => s.key !== state.currentKey);
            applySidebarFilters();
            
            // Select next key if matches exist
            if (state.filteredStrings.length > 0) {
                selectKey(state.filteredStrings[0].key);
            } else {
                // If filter is empty, fallback to another active tab
                const hasUntranslated = state.strings.some(s => s.status === 'untranslated');
                const hasOutdated = state.strings.some(s => s.status === 'outdated');
                if (hasUntranslated) {
                    state.activeFilter = 'untranslated';
                } else if (hasOutdated) {
                    state.activeFilter = 'outdated';
                } else {
                    state.activeFilter = 'all';
                }
                
                el.filterTabs.forEach(tab => {
                    if (tab.dataset.filter === state.activeFilter) tab.classList.add('active');
                    else tab.classList.remove('active');
                });
                
                applySidebarFilters();
                if (state.filteredStrings.length > 0) {
                    selectKey(state.filteredStrings[0].key);
                } else if (state.strings.length > 0) {
                    selectKey(state.strings[0].key);
                }
            }
        } else {
            alert("Error pruning string from disk.");
        }
    })
    .catch(err => {
        el.btnPruneKey.disabled = false;
        el.btnPruneKey.textContent = "Prune Translation";
        alert(`Pruning failed: ${err.message}`);
    });
}

// App Initialization Entry
document.addEventListener('DOMContentLoaded', () => {
    setupEventListeners();
    showDashboard();
});
