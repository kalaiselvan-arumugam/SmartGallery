/**
 * SmartGallery — app.js
 * Single-page application logic using jQuery 3.7.
 *
 * Modules:
 *   - Gallery   : renders image grid, infinite scroll, view modes
 *   - Search    : text search (debounced), visual search (upload/drop)
 *   - Settings  : modal, HF token, model download via SSE, folder management
 *   - DetailPanel : image metadata, tags editor, find similar
 *   - Toasts    : non-blocking notifications
 *   - Polling   : index status, model status refresh
 */
'use strict';

const SmartGallery = (() => {

  // ─── State ──────────────────────────────────────────────────────────
  const state = {
    results: [],          // current result set
    offset: 0,            // for pagination / infinite scroll
    loading: false,       // search or scroll in progress
    hasMore: false,       // more results available
    viewMode: 'grid',     // 'grid' | 'list'
    thumbCols: 5,         // current grid column count
    selectedId: null,     // currently selected image id
    currentQuery: '',
    currentFilters: {},
    modelReady: false,
    sseSource: null,      // active EventSource for model download
    activeTab: 'models',  // settings modal active tab
    watchedFolders: [],   // Added as per diff
    currentLightboxIndex: -1, // Added as per diff
    mapVisible: true,     // whether map is shown in UI
    exifVisible: true,    // Global EXIF display toggle
  };

  // ─── DOM refs (jQuery) ───────────────────────────────────────────────
  const $grid = $('#gallery-grid');
  const $emptyState = $('#empty-state');
  const $loading = $('#loading-indicator');
  const $detailPanel = $('#detail-panel');
  const $searchInput = $('#search-input');
  const $sentinel = $('#scroll-sentinel');
  const $indexPill = $('#index-status-pill');
  const $pillText = $('#index-pill-text');
  const $resultCount = $('#result-count');
  const $footerCount = $('#footer-count');
  const $countAll = $('#count-all');
  const $countFavorites = $('#count-favorites');

  // ─── Init ────────────────────────────────────────────────────────────
  function init() {
    bindEvents();
    loadModelStatus();
    loadIndexStatus();
    loadSidebarFolders();
    fetchAllPhotos();
    initInfiniteScroll();
    loadAdvancedSettings();

    // Poll model + index status every 8 seconds
    setInterval(loadModelStatus, 8000);
    setInterval(loadIndexStatus, 5000);
  }

  // ─── Event Bindings ─────────────────────────────────────────────────
  function bindEvents() {
    // Search bar — debounced
    $searchInput.on('input', debounce(() => {
      const q = $searchInput.val().trim();
      state.currentQuery = q;
      state.results = [];
      state.offset = 0;
      if (q === '') { fetchAllPhotos(); } else { doSearch(q); }
    }, 450));

    $searchInput.on('keydown', e => {
      if (e.key === 'Enter') {
        clearTimeout(SmartGallery._searchTimer);
        const q = $searchInput.val().trim();
        state.currentQuery = q;
        state.results = [];
        state.offset = 0;
        if (q === '') {
          $('#clear-search-btn').hide();
          fetchAllPhotos();
        } else {
          doSearch(q);
        }
      }
    });

    $('#clear-search-btn').on('click', () => {
      $searchInput.val('').attr('placeholder', "Search images — try: 'sunset over mountains'");
      $('#clear-search-btn').hide();
      state.currentQuery = '';
      state.offset = 0;

      // Figure out which tab was active and refresh it
      if ($('#nav-recent').hasClass('active')) {
        browse('recent');
      } else if ($('#nav-favorites').hasClass('active')) {
        browse('favorites');
      } else if ($('.folder-item.active').length > 0) {
        // If they were inside a specific folder, reload it
        const folderEl = $('.folder-item.active').get(0);
        // We injected the onclick="SmartGallery.browseFolder('path', this)"
        // Let's just click it to re-trigger the folder browse logic
        folderEl.click();
      } else {
        browse('all');
      }
    });

    // Keyboard shortcuts for Lightbox
    $(document).on('keydown', e => {
      if ($('#lightbox').is(':visible')) {
        if (e.key === 'Escape') closeLightbox();
        else if (e.key === 'ArrowRight') navLightbox(1);
        else if (e.key === 'ArrowLeft') navLightbox(-1);
      } else if ($detailPanel.hasClass('visible')) {
        if (e.key === 'Escape') closeDetailPanel();
        else if (e.key === 'ArrowRight') navSelection(1);
        else if (e.key === 'ArrowLeft') navSelection(-1);
      }
    });

    // Close lightbox if clicking outside image
    $('#lightbox').on('click', e => {
      if (e.target.id === 'lightbox') {
        closeLightbox();
      }
    });

    // Close detail panel if clicking outside it (including clicking images)
    $(document).on('mousedown', e => {
      const $panel = $('#detail-panel');
      if ($panel.hasClass('visible')) {
        // If clicking outside the panel and not a right-click
        if ($(e.target).closest('#detail-panel').length === 0 && e.button !== 2) {
          closeDetailPanel();
        }
      }
    });

    // ─── Lightbox Zoom & Drag-to-Pan ───
    let lbDrag = { isDragging: false, isMoved: false, startX: 0, startY: 0, translateX: 0, translateY: 0 };
    const $lbImg = $('#lightbox-img');

    $lbImg.on('mousedown pointerdown touchstart', function (e) {
      if (!$lbImg.hasClass('zoomed')) return; // Only drag when zoomed
      e.preventDefault(); // Prevent native image drag
      lbDrag.isDragging = true;
      lbDrag.isMoved = false;
      const clientX = e.clientX || (e.originalEvent.touches && e.originalEvent.touches[0].clientX);
      const clientY = e.clientY || (e.originalEvent.touches && e.originalEvent.touches[0].clientY);
      lbDrag.startX = clientX - lbDrag.translateX;
      lbDrag.startY = clientY - lbDrag.translateY;
      $lbImg.addClass('dragging');
    });

    $(window).on('mousemove pointermove touchmove', function (e) {
      if (!lbDrag.isDragging) return;
      lbDrag.isMoved = true;
      const clientX = e.clientX || (e.originalEvent.touches && e.originalEvent.touches[0].clientX);
      const clientY = e.clientY || (e.originalEvent.touches && e.originalEvent.touches[0].clientY);
      lbDrag.translateX = clientX - lbDrag.startX;
      lbDrag.translateY = clientY - lbDrag.startY;

      // Apply drag translation. The image is scaled (x2.5) via CSS, so we just translate it.
      $lbImg.css('transform', `scale(2.5) translate(${lbDrag.translateX / 2.5}px, ${lbDrag.translateY / 2.5}px)`);
    });

    $(window).on('mouseup pointerup touchend', function (e) {
      if (lbDrag.isDragging) {
        lbDrag.isDragging = false;
        $lbImg.removeClass('dragging');
        // If it was a drag, stop here so click handler doesn't zoom out
      }
    });

    $lbImg.on('click', function (e) {
      e.stopPropagation(); // prevent modal close
      if (lbDrag.isMoved) {
        // Was a drag, not a click. Don't toggle zoom.
        lbDrag.isMoved = false;
        return;
      }

      if ($lbImg.hasClass('zoomed')) {
        // Zoom out
        $lbImg.removeClass('zoomed').css('transform', '');
        lbDrag.translateX = 0;
        lbDrag.translateY = 0;
      } else {
        // Zoom in
        const offset = $lbImg.offset();
        // Calculate click point relative to center to determine initial translation matrix
        // For simplicity: just zoom into center and allow user to drag. Or calculate offset:
        const xP = ((e.pageX - offset.left) / $lbImg.width()) * 100;
        const yP = ((e.pageY - offset.top) / $lbImg.height()) * 100;
        $lbImg.css('transform-origin', `${xP}% ${yP}%`).addClass('zoomed');
      }
    });

    // Visual search — file input
    $('#visual-search-file-input').on('change', function () {
      const file = this.files[0];
      if (file) { doVisualSearch(file); }
      this.value = '';
    });

    // Reindex button
    $('#btn-reindex').on('click', reindex);

    // Thumbnail size slider
    $('#thumb-size-slider').on('input', function () {
      const v = +this.value;
      // Map slider (1-100) to columns (2-8)
      const cols = Math.max(2, Math.round(8 - (v / 100) * 6));
      state.thumbCols = cols;
      $grid[0].style.setProperty('--cols', cols);
    });

    // Drag and drop visual search
    $(document).on('dragover', e => {
      e.preventDefault();
      $('#drop-zone-overlay').addClass('visible');
    });
    $(document).on('dragleave drop', e => {
      $('#drop-zone-overlay').removeClass('visible');
    });
    $('#drop-zone-overlay').on('drop', e => {
      e.preventDefault();
      const file = e.originalEvent.dataTransfer.files[0];
      if (file && file.type.startsWith('image/')) {
        doVisualSearch(file);
      }
    });

    // Global context menu disable
    $(document).on('contextmenu', e => e.preventDefault());

    // Close modal on Escape
    $(document).on('keydown', e => {
      if (e.key === 'Escape') closeSettings();
    });
  }

  // ─── Search ─────────────────────────────────────────────────────────
  function doSearch(query) {
    setLoading(true);
    if (query) $('#clear-search-btn').show(); else $('#clear-search-btn').hide();
    const filters = buildFilters() || {};
    // If we're on the favorites tab, enforce the favorite tag filter for the search
    if ($('#nav-favorites').hasClass('active')) {
      if (!filters.tags) filters.tags = [];
      if (!filters.tags.includes('__sys_favorite__')) filters.tags.push('__sys_favorite__');
    }
    const payload = {
      query,
      filters: Object.keys(filters).length ? filters : null,
      limit: 200,
      offset: state.offset
    };
    $.ajax({
      url: '/api/search', method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(payload),
      success: data => {
        if (state.offset === 0) state.results = [];
        state.results = state.results.concat(data.results || []);
        state.hasMore = (data.results || []).length >= 200;
        renderGallery(state.offset === 0);
        setLoading(false);
        if (data.totalCount !== undefined && data.totalCount > state.results.length) {
          $resultCount.text(state.results.length + ' of ' + data.totalCount);
        } else {
          $resultCount.text(state.results.length);
        }
      },
      error: (xhr) => {
        showToast('Search failed: ' + (xhr.responseJSON?.error || xhr.statusText), 'error');
        setLoading(false);
      }
    });
  }

  function fetchAllPhotos() {
    setLoading(true);
    $.ajax({
      url: '/api/search', method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ query: '', filters: buildFilters(), limit: 200, offset: state.offset }),
      success: data => {
        if (state.offset === 0) state.results = [];
        state.results = state.results.concat(data.results || []);
        state.hasMore = (data.results || []).length >= 200;
        renderGallery(state.offset === 0);
        setLoading(false);
        if (data.totalCount !== undefined && data.totalCount > state.results.length) {
          $resultCount.text(state.results.length + ' of ' + data.totalCount);
        } else {
          $resultCount.text(state.results.length);
        }
      },
      error: () => setLoading(false)
    });
  }

  function doVisualSearch(file) {
    if (!state.modelReady) {
      showToast('CLIP models not ready. Download them in Settings first.', 'error');
      return;
    }
    const fd = new FormData();
    fd.append('file', file);
    setLoading(true);
    $searchInput.val('').attr('placeholder', 'Visual search: ' + file.name);
    $('#clear-search-btn').show();
    const filters = buildFilters() || {};
    // If we're on the favorites tab, enforce the favorite tag filter for visual search too
    if ($('#nav-favorites').hasClass('active')) {
      if (!filters.tags) filters.tags = [];
      if (!filters.tags.includes('__sys_favorite__')) filters.tags.push('__sys_favorite__');
    }
    if (Object.keys(filters).length) {
      fd.append('filters', JSON.stringify(filters));
    }

    $.ajax({
      url: '/api/search/image', method: 'POST',
      data: fd, processData: false, contentType: false,
      success: data => {
        state.results = data.results || [];
        state.hasMore = false;
        state.currentQuery = '';
        renderGallery(true);
        setLoading(false);
        $resultCount.text(state.results.length);
        showToast('Visual search complete — ' + state.results.length + ' similar images found', 'success');
      },
      error: xhr => {
        showToast('Visual search failed: ' + (xhr.responseJSON?.error || xhr.statusText), 'error');
        setLoading(false);
        $searchInput.attr('placeholder', "Search images — try: 'sunset over mountains'");
      }
    });
  }

  // ─── Gallery Rendering ───────────────────────────────────────────────
  function renderGallery(resetScroll) {
    if (resetScroll) {
      $grid.empty();
      if (state.results.length === 0) {
        $emptyState.show();
        $grid.hide();
        return;
      }
      $emptyState.hide();
      $grid.show();
    }

    const isListView = state.viewMode === 'list';
    const fragment = document.createDocumentFragment();

    state.results.forEach((item, index) => {
      // Skip if card already exists in the DOM
      if ($('#card-' + item.id).length) return;

      const card = document.createElement('div');
      card.className = 'gallery-card';
      card.id = 'card-' + item.id;
      card.dataset.id = item.id;
      card.dataset.score = item.score || 0;

      const scorePercent = Math.round((item.score || 0) * 100);
      const scoreLabel = item.score > 0 ? (scorePercent + '% match') : '';
      const fileName = item.fileName || '';
      const thumbSrc = item.thumbUrl || '';

      const isFav = item.loved === true;
      const starClass = isFav ? 'card-favorite active' : 'card-favorite';
      const blurOverlay = item.blurred ? '<div class="blur-overlay"><span class="material-symbols-outlined" style="font-size:32px">visibility_off</span></div>' : '';
      const imgClass = item.blurred ? 'blurred-image' : '';

      if (isListView) {
        card.innerHTML = `
          <img class="${imgClass}" src="${thumbSrc}" alt="${escHtml(fileName)}" loading="lazy" onerror="this.src='/placeholder.svg'"/>
          <div class="list-meta">
            <div class="list-filename">${escHtml(fileName)}</div>
            <div class="list-detail">${item.width || '?'}×${item.height || '?'} · ${formatSize(item.fileSize)}</div>
          </div>
          ${item.score > 0 ? `<div class="list-score">${scorePercent}%</div>` : ''}`;
      } else {
        card.innerHTML = `
          <img class="${imgClass}" src="${thumbSrc}" alt="${escHtml(fileName)}" loading="lazy" onerror="this.style.background='var(--active)'"/>
          <div class="card-overlay">
            <div class="card-filename">${escHtml(fileName)}</div>
            ${scoreLabel ? `<div class="card-score">${scoreLabel}</div>` : ''}
          </div>
          <div class="card-checkbox"><span class="material-symbols-outlined" style="font-size:12px">check</span></div>
          <span class="material-symbols-outlined ${starClass}">favorite</span>
          ${blurOverlay}`;
      }

      // Event handling for single vs double click
      let clickTimer = null;
      $(card).on('click', e => {
        if (!e.ctrlKey && e.button === 0) {
          if (clickTimer) clearTimeout(clickTimer);
          clickTimer = setTimeout(() => {
            selectImage(item, index, false);
          }, 250);
        }
      });

      $(card).on('dblclick', e => {
        if (!e.ctrlKey && e.button === 0) {
          if (clickTimer) clearTimeout(clickTimer); // cancel single click
          if (item.blurred) {
            showToast('Privacy blur enabled. Untoggle to preview.', 'info');
            return;
          }
          openLightbox(item, index);
        }
      });

      // Handle Favorite Heart Click
      $(card).find('.card-favorite').on('click', function (e) {
        e.stopPropagation();
        toggleFavorite(item, this);
      });

      // Handle Right Click (Select and Show Detail Panel)
      card.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        selectImage(item, index, true);
      });

      fragment.appendChild(card);
    });

    $grid[0].appendChild(fragment);
    $grid[0].style.setProperty('--cols', state.thumbCols);
  }

  // ─── Image Selection + Detail Panel ─────────────────────────────────
  function selectImage(item, idx, openSidebar = true) {
    state.selectedId = item.id;
    state.currentLightboxIndex = idx;

    // Update highlight
    $('.gallery-card').removeClass('selected');
    $(`#card-${item.id}`).addClass('selected');

    // Populate detail panel (for right sidebar)
    $('#detail-filename').text(item.fileName || 'Unknown');
    $('#detail-date').text(formatDate(item.lastModified));

    const $dThumb = $('#detail-thumb');
    $dThumb.attr('src', item.thumbUrl).show();
    $dThumb.toggleClass('blurred-image', !!item.blurred);

    const score = item.score || 0;
    $('#detail-score-bar').css('width', Math.round(score * 100) + '%');
    $('#detail-score-text').text(score > 0 ? (Math.round(score * 10000) / 100) + '% similarity' : 'N/A');
    $('#dp-size').text(formatSize(item.fileSize));
    $('#dp-dims').text(item.width && item.height ? item.width + '×' + item.height : '—');
    $('#dp-date').text(formatDate(item.lastModified));
    $('#dp-indexed').text(formatDate(item.indexedAt));
    $('#dp-path').text(item.filePath || '—');

    // Update Privacy Toggle UI
    const $pBtn = $('#privacy-toggle-btn');
    if (item.blurred) {
      $pBtn.addClass('active').find('.material-symbols-outlined').text('visibility_off');
    } else {
      $pBtn.removeClass('active').find('.material-symbols-outlined').text('visibility');
    }

    // EXIF + Map
    const $mapSection = $('#dp-map-section');
    const $exifSection = $('#dp-exif-section');

    if (item.blurred || !state.exifVisible) {
      $mapSection.hide();
      $exifSection.hide();
    } else {
      if (state.mapVisible && item.latitude && item.longitude) {
        $mapSection.show();
        if (!window.detailMap) {
          window.detailMap = L.map('detail-map').setView([item.latitude, item.longitude], 13);
          L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '© OpenStreetMap'
          }).addTo(window.detailMap);
          window.detailMapMarker = L.marker([item.latitude, item.longitude]).addTo(window.detailMap);
        } else {
          window.detailMap.setView([item.latitude, item.longitude], 13);
          window.detailMapMarker.setLatLng([item.latitude, item.longitude]);
          setTimeout(() => window.detailMap.invalidateSize(), 150);
        }
      } else {
        $mapSection.hide();
      }

      const $exifGrid = $('#dp-exif-grid');
      $exifGrid.empty();
      let hasExif = false;
      if (item.extraJson) {
        try {
          const extra = JSON.parse(item.extraJson);
          if (extra.exif && Object.keys(extra.exif).length > 0) {
            for (const [k, v] of Object.entries(extra.exif)) {
              $exifGrid.append(`<span class="detail-key">${escHtml(k)}</span><span class="detail-val" style="text-align:right;">${escHtml(v)}</span>`);
            }
            hasExif = true;
          }
        } catch (e) { }
      }
      if (hasExif) $exifSection.show();
      else $exifSection.hide();
    }

    // Tags
    let tagsHtml = '<span class="tag-add-btn" onclick="SmartGallery.addTagToSelected()">+ Add Tag</span>';
    if (item.extraJson) {
      try {
        const extra = JSON.parse(item.extraJson);
        const tags = extra.tags || [];
        tags.forEach(t => {
          if (t === '__sys_favorite__') return;
          const delBtn = `<span class="remove-tag" onclick="SmartGallery.removeTagFromSelected('${escAttr(t)}')">×</span>`;
          tagsHtml = `<span class="tag-badge">${escHtml(t)}${delBtn}</span>` + tagsHtml;
        });
      } catch (e) { /* ignore */ }
    }
    $('#detail-tags').html(tagsHtml);

    if (openSidebar) {
      $detailPanel.addClass('visible');
    }
  }

  function toggleFavorite(item, starEl) {
    let extra = {};
    if (item.extraJson) { try { extra = JSON.parse(item.extraJson); } catch (e) { } }
    if (!extra.tags) extra.tags = [];

    const isFav = item.loved === true;
    if (isFav) {
      item.loved = false;
      extra.tags = extra.tags.filter(t => t !== '__sys_favorite__');
      $(starEl).removeClass('active');
    } else {
      item.loved = true;
      extra.tags.push('__sys_favorite__');
      $(starEl).addClass('active');
    }
    item.extraJson = JSON.stringify(extra);

    $.ajax({
      url: '/api/images/' + item.id + '/tags', method: 'PATCH',
      contentType: 'application/json', data: item.extraJson,
      success: () => {
        if (state.selectedId === item.id) {
          showDetailPanel(item); // Refresh tags array in side panel
        }
        // If we are currently viewing the Favorites page, remove the item from the DOM eagerly
        if (isFav && $('#nav-favorites').hasClass('active')) {
          $('#card-' + item.id).fadeOut(200, function () {
            $(this).remove();
            // Also remove from state.results
            state.results = state.results.filter(r => r.id !== item.id);
            // Update counter
            $resultCount.text(state.results.length);
            // If empty, show empty state
            if (state.results.length === 0) {
              $emptyState.show();
              $grid.hide();
            }
          });
        }
      }
    });
  }

  function closeDetailPanel() {
    $detailPanel.removeClass('visible');
    $('.gallery-card').removeClass('selected');
    state.selectedId = null;
  }

  /* ─── Lightbox Fullscreen ────────────────────────────────────────────── */
  function openLightbox(item, idx) {
    state.currentLightboxIndex = idx;
    const $lb = $('#lightbox');
    const $img = $('#lightbox-img');
    const $caption = $('#lightbox-caption');

    $img.attr('src', '').removeClass('zoomed').css('transform', '').css('transform-origin', 'center center'); // clear previous and reset zoom

    // Append timestamp to prevent aggressive browser caching on the full res image route
    $img.attr('src', '/api/images/' + item.id + '/full?t=' + Date.now());
    $caption.text(item.fileName + ' (' + (idx + 1) + ' of ' + state.results.length + ')');
    $lb.fadeIn(150);
  }

  function closeLightbox() {
    $('#lightbox').fadeOut(150);
    $('#lightbox-img').removeClass('zoomed').css('transform', '').css('transform-origin', 'center center');
  }

  function navLightbox(direction) {
    // Force direction to be an integer (HTML inline passes it correctly but just in case)
    const dir = parseInt(direction, 10);
    let newIdx = state.currentLightboxIndex + dir;
    if (newIdx < 0) newIdx = 0;
    if (newIdx >= state.results.length) newIdx = state.results.length - 1;

    if (newIdx !== state.currentLightboxIndex) {
      const item = state.results[newIdx];
      openLightbox(item, newIdx);
    }
  }

  function showDetailPanel(item) {
    // Redirect to the modern, EXIF-rich selectImage function to ensure UI consistency
    const idx = state.results.findIndex(r => r.id === item.id);
    if (idx !== -1) {
      selectImage(item, idx, true);
    }
  }

  function navSelection(dir) {
    if (!state.selectedId) return;
    const currentIdx = state.results.findIndex(r => r.id === state.selectedId);
    if (currentIdx === -1) return;
    let newIdx = currentIdx + dir;
    if (newIdx < 0) newIdx = 0;
    if (newIdx >= state.results.length) newIdx = state.results.length - 1;

    if (newIdx !== currentIdx) {
      selectImage(state.results[newIdx], newIdx);
      const card = document.getElementById('card-' + state.results[newIdx].id);
      if (card) {
        card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
    }
  }

  function addTagToSelected() {
    if (!state.selectedId) return;

    if ($('#inline-tag-input').length > 0) {
      $('#inline-tag-input').focus();
      return;
    }

    const $btn = $('.tag-add-btn');
    const $input = $('<input type="text" id="inline-tag-input" class="form-input" placeholder="Tag..." style="width: 100px; display: inline-block; padding: 2px 8px; font-size: 11px; margin-left: 4px; border-radius: 999px; height: 22px; background: var(--bg); border: 1px solid var(--border); color: var(--text);">');

    $btn.hide().after($input);
    $input.focus();

    const save = function (tag) {
      if ($input.data('saving')) return;
      $input.data('saving', true);

      if (!tag || !tag.trim()) {
        $input.remove();
        $btn.show();
        return;
      }

      if (!state.selectedId) {
        $input.remove();
        $btn.show();
        return;
      }

      const item = state.results.find(r => r.id === state.selectedId);
      let extra = {};
      if (item?.extraJson) { try { extra = JSON.parse(item.extraJson); } catch (e) { } }
      if (!extra.tags) extra.tags = [];

      if (extra.tags.includes(tag.trim())) {
        $input.remove();
        $btn.show();
        return;
      }

      extra.tags.push(tag.trim());
      $input.prop('disabled', true);

      $.ajax({
        url: '/api/images/' + state.selectedId + '/tags', method: 'PATCH',
        contentType: 'application/json', data: JSON.stringify(extra),
        success: () => {
          if (item) {
            item.extraJson = JSON.stringify(extra);
            // Re-render detail panel tags mapping specifically to preserve visual layout seamlessly
            showDetailPanel(item);
          }
          showToast('Tag added', 'success');
        },
        error: () => {
          showToast('Failed to save tag', 'error');
          $input.remove();
          $btn.show();
        }
      });
    };

    $input.on('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        save($(this).val());
      } else if (e.key === 'Escape') {
        $(this).remove();
        $btn.show();
      }
    });

    $input.on('blur', function () {
      save($(this).val());
    });
  }

  function removeTagFromSelected(tag) {
    if (!state.selectedId || !tag) return;

    // Stop event propagation to prevent unexpected UI clicks
    if (window.event) window.event.stopPropagation();

    const item = state.results.find(r => r.id === state.selectedId);
    if (!item) return;

    let extra = {};
    if (item.extraJson) { try { extra = JSON.parse(item.extraJson); } catch (e) { } }
    if (!extra.tags) return;

    extra.tags = extra.tags.filter(t => t !== tag);

    $.ajax({
      url: '/api/images/' + state.selectedId + '/tags', method: 'PATCH',
      contentType: 'application/json', data: JSON.stringify(extra),
      success: () => {
        item.extraJson = JSON.stringify(extra);
        showDetailPanel(item);
        showToast('Tag removed', 'success');
      },
      error: () => showToast('Failed to remove tag', 'error')
    });
  }

  function findSimilar() {
    if (!state.selectedId) return;
    showToast('Finding similar images...', 'info');
    // Fetch and re-search with the selected image as query
    const item = state.results.find(r => r.id === state.selectedId);
    if (!item) return;
    // Trigger visual search by fetching the thumbnail and converting to blob
    fetch(item.thumbUrl)
      .then(r => r.blob())
      .then(blob => {
        const file = new File([blob], item.fileName || 'image.jpg', { type: 'image/jpeg' });
        doVisualSearch(file);
      })
      .catch(() => showToast('Could not load image for similarity search', 'error'));
  }

  function openFile() {
    const item = state.results.find(r => r.id === state.selectedId);
    if (!item) return;
    // Show file path as info (cannot open OS file directly from browser)
    showToast('File: ' + (item.filePath || '—'), 'info');
  }

  // ─── Browsing ────────────────────────────────────────────────────────
  function browse(mode) {
    $('.sidebar-item').removeClass('active');

    state.offset = 0;
    state.results = [];
    $searchInput.val('').attr('placeholder', "Search images — try: 'sunset over mountains'");
    state.currentQuery = '';

    if (mode === 'all') {
      $('#nav-all').addClass('active');
      fetchAllPhotos();
    } else if (mode === 'recent') {
      $('#nav-recent').addClass('active');
      doSearch('');
    } else if (mode === 'favorites') {
      $('#nav-favorites').addClass('active');
      $.ajax({
        url: '/api/search/tags?tag=__sys_favorite__&limit=100', method: 'GET',
        success: data => {
          state.results = data.results || [];
          state.hasMore = false;
          renderGallery(true);
          $resultCount.text(state.results.length);
        },
        error: () => {
          state.results = [];
          renderGallery(true);
          $resultCount.text(0);
          showToast('Failed to load favorites', 'error');
        }
      });
    } else if (mode === 'folder') { /* folder click handled below */ }
  }

  function browseFolder(folderPath, $btn) {
    $('.sidebar-item, .folder-item').removeClass('active');
    $btn.addClass('active');
    state.results = [];
    state.offset = 0;
    state.currentQuery = '';
    $.ajax({
      url: '/api/search/browse?folder=' + encodeURIComponent(folderPath) + '&limit=200',
      success: data => {
        state.results = data.results || [];
        state.hasMore = false;
        renderGallery(true);
        $resultCount.text(state.results.length);
      },
      error: () => {
        state.results = [];
        renderGallery(true);
        $resultCount.text(0);
        showToast('Failed to load folder', 'error');
      }
    });
  }

  function setFilter(filter, el) {
    $('.filter-chip').removeClass('active');
    $(el).addClass('active');
  }

  // ─── Filters ─────────────────────────────────────────────────────────
  function buildFilters() {
    return null;
  }

  // ─── View Modes ───────────────────────────────────────────────────────
  function setView(mode) {
    state.viewMode = mode;
    if (mode === 'list') {
      $grid.addClass('list-view');
      $('#view-list').addClass('active'); $('#view-grid').removeClass('active');
    } else {
      $grid.removeClass('list-view');
      $('#view-grid').addClass('active'); $('#view-list').removeClass('active');
    }
    // Re-render with list-view markup
    state.offset = 0;
    const savedResults = [...state.results];
    state.results = [];
    $grid.empty();
    state.results = savedResults;
    renderGallery(true);
  }

  // ─── Infinite Scroll ─────────────────────────────────────────────────
  function initInfiniteScroll() {
    const observer = new IntersectionObserver(entries => {
      if (entries[0].isIntersecting && state.hasMore && !state.loading) {
        state.offset += 200;
        if (state.currentQuery) {
          doSearch(state.currentQuery);
        } else {
          fetchAllPhotos();
        }
      }
    }, { rootMargin: '200px' });
    observer.observe($sentinel[0]);
  }

  // ─── Loading State ────────────────────────────────────────────────────
  function setLoading(active) {
    state.loading = active;
    if (active) { $loading.addClass('visible'); } else { $loading.removeClass('visible'); }
  }

  // ─── Model Status ─────────────────────────────────────────────────────
  function loadModelStatus() {
    $.getJSON('/api/models/status', data => {
      const status = data.status;
      state.modelReady = (status === 'READY');

      const $badge = $('#model-status-badge');
      const $txt = $('#model-status-text');
      $badge.removeClass('ready notready error');

      if (status === 'READY') {
        $badge.addClass('ready'); $txt.text('Models Ready');
      } else if (status === 'ERROR') {
        $badge.addClass('error'); $txt.text('Model Error');
      } else if (status === 'DOWNLOADING') {
        $badge.addClass('notready'); $txt.text('Downloading...');
      } else {
        $badge.addClass('notready');
        $txt.text(status === 'NOT_DOWNLOADED' ? 'Models Not Downloaded' : 'Models Partial');
      }

      // Update settings modal if open
      updateModalModelStatus(data);
    });
  }

  function updateModalModelStatus(data) {
    const $badge = $('#modal-model-badge');
    const $list = $('#model-files-list');
    if (!$badge.length) return;

    const statusColors = {
      READY: { bg: 'rgba(34,197,94,0.1)', color: '#22c55e' },
      NOT_DOWNLOADED: { bg: 'var(--active)', color: 'var(--text-dim)' },
      PARTIAL: { bg: 'rgba(234,179,8,0.1)', color: '#eab308' },
      DOWNLOADING: { bg: 'rgba(43,117,238,0.1)', color: 'var(--primary)' },
      ERROR: { bg: 'rgba(239,68,68,0.1)', color: 'var(--red)' }
    };
    const sc = statusColors[data.status] || statusColors.NOT_DOWNLOADED;
    $badge.text(data.status).css({ background: sc.bg, color: sc.color });

    if (data.files && data.files.length) {
      const html = data.files.map(f => `
        <div class="model-file-item">
          <span class="material-symbols-outlined model-file-icon" style="color:${f.exists ? 'var(--green)' : 'var(--text-dim)'}">
            ${f.exists ? 'check_circle' : 'radio_button_unchecked'}
          </span>
          <span class="model-file-name">${escHtml(f.name)}</span>
          <span class="model-file-size">${f.exists ? formatSize(f.sizeBytes) : '—'}</span>
        </div>`).join('');
      $list.html(html);
    }
  }

  // ─── Index Status Polling ─────────────────────────────────────────────
  function loadIndexStatus() {
    $.getJSON('/api/index/status', data => {
      const count = data.totalIndexed || 0;
      $countAll.text(count);
      $countFavorites.text(data.favoritesCount || 0);

      // Auto-increment the UI results count if we are viewing the unfiltered 'All Photos' tab
      if (state.totalIndexed !== undefined && count > state.totalIndexed) {
        if ($('#nav-all').hasClass('active') && !state.currentQuery && !buildFilters()) {
          $resultCount.text(count);
        }
      }
      state.totalIndexed = count;

      if (data.processedCount > 0 || data.currentFile) {
        $indexPill.addClass('visible');
        $pillText.text('Indexing: ' + (data.currentFile || count + ' done'));
      } else {
        $indexPill.removeClass('visible');
      }
    });
  }

  // ─── Reindex ──────────────────────────────────────────────────────────
  function reindex() {
    $indexPill.addClass('visible');
    $pillText.text('Starting reindex...');
    $.ajax({
      url: '/api/index/reindex', method: 'POST',
      success: data => {
        showToast(data.message || 'Reindexing started', 'success');
      },
      error: () => showToast('Failed to start reindex', 'error')
    });
  }

  function triggerReindexFromSettings() {
    reindex();
    addLog('Reindex triggered for all watched folders.', 'info');
  }

  // ─── Settings Modal ───────────────────────────────────────────────────
  function openSettings(tab) {
    $('#modal-overlay').addClass('visible');
    loadTokenStatus();
    loadFoldersList();
    loadModelStatus();
    if (tab) switchTab(tab, $('[data-tab="' + tab + '"]')[0]);
  }

  function closeSettings(e) {
    if (e && $(e.target).is('#settings-modal, #settings-modal *')) return;
    $('#modal-overlay').removeClass('visible');
    if (state.sseSource) {
      state.sseSource.close();
      state.sseSource = null;
    }
  }

  function switchTab(tab, el) {
    state.activeTab = tab;
    $('.modal-tab').css({ color: 'var(--text-muted)', borderBottomColor: 'transparent', fontWeight: '500' });
    $(el).css({ color: 'var(--primary)', borderBottomColor: 'var(--primary)', fontWeight: '600' });
    $('.tab-pane').hide();
    $('#tab-' + tab).show();
    if (tab === 'folders') loadFoldersList();
    if (tab === 'advanced') loadAdvancedSettings();
  }

  function loadAdvancedSettings() {
    $.getJSON('/api/settings/advanced', data => {
      state.exifVisible = data.exifVisible;
      state.mapVisible = data.mapVisible;
      $('#exif-visible-toggle').prop('checked', data.exifVisible);
      $('#map-visible-toggle').prop('checked', data.mapVisible);
      $('#exif-parsing-toggle').prop('checked', data.exifEnabled);
      $('#auto-indexing-toggle').prop('checked', data.autoIndexingEnabled);
      $('#threshold-slider').val(data.searchThreshold);
      $('#threshold-val').text(data.searchThreshold.toFixed(2));
    });
  }

  function saveAdvancedSettings() {
    const exifVisible = $('#exif-visible-toggle').is(':checked');
    const mapVisible = $('#map-visible-toggle').is(':checked');
    const exifEnabled = $('#exif-parsing-toggle').is(':checked');
    const autoIndexingEnabled = $('#auto-indexing-toggle').is(':checked');
    const searchThreshold = parseFloat($('#threshold-slider').val());

    $.ajax({
      url: '/api/settings/advanced',
      method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ exifVisible, mapVisible, exifEnabled, autoIndexingEnabled, searchThreshold }),
      success: () => {
        state.exifVisible = exifVisible;
        state.mapVisible = mapVisible;
        showToast('Advanced settings saved', 'success');
        // Refresh detail panel if open
        if (state.selectedId) {
          const item = state.results.find(res => res.id === state.selectedId);
          if (item) selectImage(item, state.currentLightboxIndex, false);
        }
      },
      error: () => showToast('Failed to save advanced settings', 'error')
    });
  }

  function loadTokenStatus() {
    $.getJSON('/api/settings/token/status', data => {
      if (data.hasToken) {
        $('#hf-token-input').attr('placeholder', '••••••• (token saved)').val('');
        $('#settings-status-msg').text('HF token is saved and encrypted on this machine.');
      } else {
        $('#hf-token-input').attr('placeholder', 'hf_...').val('');
        $('#settings-status-msg').text('No token saved. Enter your Hugging Face token above.');
      }
    });
  }

  function saveToken() {
    const token = $('#hf-token-input').val().trim();
    if (!token) { showToast('Enter a Hugging Face token first', 'error'); return; }
    $.ajax({
      url: '/api/settings/token', method: 'POST',
      contentType: 'application/json', data: JSON.stringify({ token }),
      success: data => {
        showToast(data.message || 'Token saved', 'success');
        $('#hf-token-input').val('').attr('placeholder', '••••••• (token saved)');
        addLog('Token saved and encrypted.', 'success');
      },
      error: xhr => showToast('Save failed: ' + (xhr.responseJSON?.error || xhr.statusText), 'error')
    });
  }

  function clearToken() {
    $.ajax({
      url: '/api/settings/token', method: 'DELETE',
      success: () => {
        showToast('Token cleared', 'info');
        $('#hf-token-input').val('').attr('placeholder', 'hf_...');
        addLog('Token cleared.', 'info');
      }
    });
  }

  // ─── Model Download (SSE) ─────────────────────────────────────────────
  function downloadModels() {
    const token = $('#hf-token-input').val().trim();
    const repo = $('#hf-repo-input').val().trim();
    const payload = { repo: repo || undefined };

    // Save token first if provided
    const doDownload = () => {
      $('#download-progress-wrap').show();
      $('#progress-fill').css('width', '0%').removeClass('error success');
      $('#progress-pct').text('0%');
      $('#progress-file-label').text('Starting...');
      $('#btn-download-models').prop('disabled', true).text('Downloading...');

      addLog('Starting download from: ' + (repo || 'Xenova/clip-vit-base-patch32'), 'info');

      // Start the download
      $.ajax({
        url: '/api/models/download', method: 'POST',
        contentType: 'application/json', data: JSON.stringify(payload),
        statusCode: {
          409: () => showToast('Download already in progress', 'error')
        },
        error: xhr => {
          if (xhr.status !== 409) { showToast('Failed to start download', 'error'); }
          $('#btn-download-models').prop('disabled', false).html('<span class="material-symbols-outlined">cloud_download</span> Download Models');
        }
      });

      // Connect SSE for progress
      if (state.sseSource) state.sseSource.close();
      state.sseSource = new EventSource('/api/models/progress');

      state.sseSource.addEventListener('progress', e => {
        const d = JSON.parse(e.data);
        const pct = d.percentage || 0;
        const status = d.status;

        $('#progress-fill').css('width', pct + '%');
        $('#progress-pct').text(pct + '%');
        $('#progress-file-label').text(d.message || d.file || status);
        addLog('[' + status + '] ' + (d.message || ''), status === 'ERROR' ? 'error' : status === 'READY' ? 'success' : 'info');

        if (status === 'READY') {
          $('#progress-fill').addClass('success');
          $('#btn-download-models').prop('disabled', false).html('<span class="material-symbols-outlined">cloud_download</span> Download Models');
          showToast('Models downloaded and loaded!', 'success');
          state.sseSource.close();
          state.sseSource = null;
          loadModelStatus();
        } else if (status === 'ERROR') {
          $('#progress-fill').addClass('error');
          $('#btn-download-models').prop('disabled', false).html('<span class="material-symbols-outlined">cloud_download</span> Download Models');
          showToast('Download error: ' + d.message, 'error');
          state.sseSource.close();
          state.sseSource = null;
        }
      });

      state.sseSource.addEventListener('status', e => {
        const d = JSON.parse(e.data);
        addLog('[STATUS] ' + d.message, 'info');
      });

      state.sseSource.onerror = () => {
        if (state.sseSource) state.sseSource.close();
      };
    };

    // Save token first if a new one was entered
    if (token) {
      $.ajax({
        url: '/api/settings/token', method: 'POST',
        contentType: 'application/json', data: JSON.stringify({ token }),
        success: doDownload,
        error: (xhr) => {
          if (xhr.status === 400) {
            showToast('Invalid token: ' + (xhr.responseJSON?.error || ''), 'error');
          } else {
            doDownload(); // Proceed even if save fails somehow
          }
        }
      });
    } else {
      doDownload();
    }
  }

  function verifyModels() {
    $.ajax({
      url: '/api/models/verify', method: 'POST',
      success: data => {
        updateModalModelStatus(data);
        addLog('Verification complete. Status: ' + data.status, data.status === 'READY' ? 'success' : 'info');
        showToast('Model status: ' + data.status, data.status === 'READY' ? 'success' : 'info');
      }
    });
  }

  // ─── Folder Management ────────────────────────────────────────────────
  function loadFoldersList() {
    $.getJSON('/api/settings/folders', data => {
      // Modal folders list
      let modalHtml = '';
      data.forEach(f => {
        modalHtml += `
          <div class="folder-entry" id="folder-entry-${f.id}">
            <span class="material-symbols-outlined" style="color:var(--primary);font-size:18px">folder</span>
            <span class="folder-entry-path">${escHtml(f.folderPath)}</span>
            <span class="folder-entry-badge">${f.active ? 'Active' : 'Paused'}</span>
            <span class="folder-entry-remove material-symbols-outlined" onclick="SmartGallery.removeFolder(${f.id})" title="Remove">close</span>
          </div>`;
      });
      $('#folders-list').html(modalHtml || '<p style="color:var(--text-dim);font-size:13px">No watched folders configured.</p>');

      // Sidebar folders
      let sidebarHtml = '';
      data.filter(f => f.active).forEach(f => {
        const name = f.folderPath.replace(/\\/g, '/').split('/').pop() || f.folderPath;
        const count = f.imageCount || 0;
        sidebarHtml += `
          <button class="folder-item" onclick="SmartGallery.browseFolder('${escAttr(f.folderPath)}', this)">
            <span class="material-symbols-outlined" style="font-size:17px;color:var(--text-dim)">folder</span>
            <span class="folder-name">${escHtml(name)}</span>
            <span class="count" style="margin-left:auto;font-size:11px;color:var(--text-dim)">${count}</span>
          </button>`;
      });
      $('#sidebar-folders').html(sidebarHtml);
    });
  }

  function addFolder() {
    const path = $('#new-folder-input').val().trim();
    if (!path) { showToast('Enter a folder path', 'error'); return; }
    $.ajax({
      url: '/api/settings/folders', method: 'POST',
      contentType: 'application/json', data: JSON.stringify({ folderPath: path }),
      success: data => {
        showToast('Folder added: ' + (data.folderPath || path), 'success');
        $('#new-folder-input').val('');
        loadFoldersList();
        loadSidebarFolders();
        addLog('Watching folder: ' + (data.folderPath || path), 'success');
        reindex(); // implicitly trigger an automatic scan for the new folder
      },
      error: xhr => showToast('Error: ' + (xhr.responseJSON?.error || xhr.statusText), 'error')
    });
  }

  function removeFolder(id) {
    showConfirm('Stop watching this folder?', () => {
      $.ajax({
        url: '/api/settings/folders/' + id, method: 'DELETE',
        success: () => {
          $('#folder-entry-' + id).remove();
          showToast('Folder removed from watch list', 'info');
          loadFoldersList();
          loadSidebarFolders();
        }
      });
    });
  }

  function loadSidebarFolders() {
    $.getJSON('/api/settings/folders', data => {
      let html = '';
      data.filter(f => f.active).forEach(f => {
        const name = f.folderPath.replace(/\\/g, '/').split('/').pop() || f.folderPath;
        const count = f.imageCount || 0;
        html += `
          <button class="folder-item" onclick="SmartGallery.browseFolder('${escAttr(f.folderPath)}', this)">
            <span class="material-symbols-outlined" style="font-size:17px;color:var(--text-dim)">folder</span>
            <span class="folder-name">${escHtml(name)}</span>
            <span class="count" style="margin-left:auto;font-size:11px;color:var(--text-dim)">${count}</span>
          </button>`;
      });
      $('#sidebar-folders').html(html);
    });
  }

  // ─── Download Log ─────────────────────────────────────────────────────
  function addLog(msg, type) {
    const $log = $('#download-log');
    const line = `<div class="log-line ${type || ''}">[${new Date().toLocaleTimeString()}] ${escHtml(msg)}</div>`;
    $log.append(line);
    $log.scrollTop($log[0].scrollHeight);
  }

  // ─── Toast Notifications ──────────────────────────────────────────────
  function showToast(message, type) {
    const icons = { success: 'check_circle', error: 'error', info: 'info' };
    const icon = icons[type] || 'info';
    const $toast = $(`
      <div class="toast ${type}">
        <span class="material-symbols-outlined toast-icon">${icon}</span>
        <span>${escHtml(message)}</span>
      </div>`);
    $('#toast-container').append($toast);
    setTimeout(() => $toast.fadeOut(300, () => $toast.remove()), 3500);
  }

  function showConfirm(message, onConfirm) {
    $('#confirm-msg').text(message);
    $('#confirm-modal').addClass('visible');
    $('#confirm-btn-ok').off('click').on('click', () => {
      $('#confirm-modal').removeClass('visible');
      if (onConfirm) onConfirm();
    });
    $('#confirm-btn-cancel').off('click').on('click', () => {
      $('#confirm-modal').removeClass('visible');
    });
  }

  // ─── Detail Panel Actions ───────────────────────────────────────────
  function togglePrivacyBlur() {
    if (!state.selectedId) return;
    const item = state.results.find(r => r.id === state.selectedId);
    if (!item) return;
    const newState = !item.blurred;
    $.ajax({
      url: '/api/images/' + item.id + '/blur?blurred=' + newState,
      method: 'PATCH',
      success: () => {
        item.blurred = newState;
        const $card = $('#card-' + item.id);
        const $pBtn = $('#privacy-toggle-btn');

        $card.find('img').toggleClass('blurred-image', newState);
        if (newState) {
          $card.append('<div class="blur-overlay"><span class="material-symbols-outlined" style="font-size:32px">visibility_off</span></div>');
          $pBtn.addClass('active').find('.material-symbols-outlined').text('visibility_off');
          showToast('Privacy blur applied', 'success');
        } else {
          $card.find('.blur-overlay').remove();
          $pBtn.removeClass('active').find('.material-symbols-outlined').text('visibility');
          showToast('Privacy blur removed', 'success');
        }
        // Refresh detail panel view for current item
        selectImage(item, state.currentLightboxIndex);
      },
      error: () => showToast('Failed to toggle blur', 'error')
    });
  }

  function deleteImage() {
    if (!state.selectedId) return;
    showConfirm('Are you sure you want to delete this image from the search index?', () => {
      $.ajax({
        url: '/api/images/' + state.selectedId, method: 'DELETE',
        success: () => {
          showToast('Image removed from index', 'info');
          $('#card-' + state.selectedId).remove();
          state.results = state.results.filter(r => r.id !== state.selectedId);
          $resultCount.text(state.results.length);
          $('#detail-panel').removeClass('visible');
        }
      });
    });
  }

  // ─── Helpers ──────────────────────────────────────────────────────────
  function debounce(fn, delay) {
    let t;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), delay); };
  }

  function escHtml(s) {
    if (!s) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function escAttr(s) {
    return (s || '').replace(/'/g, "\\'").replace(/"/g, '&quot;');
  }

  function formatSize(bytes) {
    if (!bytes) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  }

  function formatDate(dtStr) {
    if (!dtStr) return '—';
    try {
      return new Date(dtStr).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    } catch (e) { return dtStr; }
  }

  // ─── Public API ───────────────────────────────────────────────────────
  return {
    init, browse, browseFolder, setFilter, setView,
    reindex,
    openSettings, closeSettings, switchTab,
    saveToken, clearToken, downloadModels, verifyModels,
    addFolder, removeFolder,
    addTagToSelected, findSimilar, openFile,
    triggerReindexFromSettings,
    togglePrivacyBlur, deleteImage,
    closeLightbox,
    navLightbox,
    closeDetailPanel,
    removeTagFromSelected,
    saveAdvancedSettings
  };

})();

// Boot
$(document).ready(() => SmartGallery.init());
