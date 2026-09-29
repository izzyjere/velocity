package zm.co.codelabs.adm.ui.browser;

import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONTokener;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import zm.co.codelabs.adm.databinding.BottomSheetMediaChooserBinding;
import zm.co.codelabs.adm.databinding.FragmentBrowserBinding;
import zm.co.codelabs.adm.ui.MainActivity;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.media.MediaDiscovery;

public final class BrowserFragment extends Fragment {
    private static final String ARG_URL = "initial_url";
    private static final int MAX_MEDIA_CANDIDATES = 50;
    private FragmentBrowserBinding binding;
    private final LinkedHashMap<String, MediaCandidate> mediaCandidates = new LinkedHashMap<>();
    private boolean addressBarVisible = true;
    private int accumulatedScroll;
    public static BrowserFragment newInstance(String url) { BrowserFragment fragment = new BrowserFragment(); Bundle args = new Bundle(); args.putString(ARG_URL, url); fragment.setArguments(args); return fragment; }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentBrowserBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        WebSettings settings = binding.web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSafeBrowsingEnabled(true); settings.setMediaPlaybackRequiresUserGesture(true);
        binding.web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { Uri uri = request.getUrl(); if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) return false; return true; }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { mediaCandidates.clear(); if (binding != null) { setAddressBarVisible(true); binding.address.setText(url); updateMediaButton(url); } }
            @Override public void onPageFinished(WebView view, String url) { if (binding == null) return; binding.address.setText(url); discoverDocumentMedia(view); updateMediaButton(url); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Map<String, String> headers = allowedRequestHeaders(request.getRequestHeaders());
                view.post(() -> collectCandidate(url, headers));
                return null;
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { if (binding != null) { binding.web.removeAllViews(); binding.web.destroy(); com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), R.string.browser_render_failure, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show(); binding.getRoot().post(() -> getParentFragmentManager().beginTransaction().replace(zm.co.codelabs.adm.R.id.content, new BrowserFragment()).commitAllowingStateLoss()); } return true; }
        });
        binding.web.setWebChromeClient(new WebChromeClient() { @Override public void onProgressChanged(WebView view, int progress) { binding.loading.setVisibility(progress < 100 ? View.VISIBLE : View.GONE); binding.loading.setProgressCompat(progress, true); } });
        binding.web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> intercept(url, userAgent, Map.of()));
        int collapseThreshold = Math.round(24 * getResources().getDisplayMetrics().density);
        binding.web.setOnScrollChangeListener((scrolledView, x, y, oldX, oldY) -> {
            int delta = y - oldY;
            if (y <= 0) { accumulatedScroll = 0; setAddressBarVisible(true); return; }
            if ((delta > 0 && accumulatedScroll < 0) || (delta < 0 && accumulatedScroll > 0)) accumulatedScroll = 0;
            accumulatedScroll += delta;
            if (accumulatedScroll > collapseThreshold) { accumulatedScroll = 0; setAddressBarVisible(false); }
            else if (accumulatedScroll < -collapseThreshold) { accumulatedScroll = 0; setAddressBarVisible(true); }
        });
        binding.web.setOnLongClickListener(v -> { WebView.HitTestResult hit = binding.web.getHitTestResult(); String url = hit == null ? null : hit.getExtra(); if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) { intercept(url, settings.getUserAgentString(), Map.of()); return true; } return false; });
        binding.mediaDownload.setOnClickListener(v -> chooseMedia());
        binding.showBrowserControls.setOnClickListener(v -> { accumulatedScroll = 0; setAddressBarVisible(true); });
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.showBrowserControls, getString(R.string.show_browser_controls));
        binding.go.setOnClickListener(v -> navigate()); binding.address.setOnEditorActionListener((v, action, event) -> { if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) { navigate(); return true; } return false; });
        String initial = getArguments() == null ? null : getArguments().getString(ARG_URL); if (initial != null) { binding.address.setText(initial); updateMediaButton(initial); binding.web.loadUrl(initial); }
    }
    private void navigate() { String value = binding.address.getText() == null ? "" : binding.address.getText().toString().trim(); if (!value.matches("(?i)^https?://.*")) value = "https://" + value; Uri uri = Uri.parse(value); if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) { binding.addressLayout.setError("Enter a valid web address"); return; } binding.addressLayout.setError(null); binding.web.loadUrl(uri.toString()); }
    private void intercept(String url, String userAgent, Map<String, String> requestHeaders) { Map<String, String> headers = new LinkedHashMap<>(requestHeaders); String cookies = CookieManager.getInstance().getCookie(url); if (cookies != null && !cookies.isBlank()) headers.put("Cookie", cookies); if (userAgent != null) headers.put("User-Agent", userAgent); String referrer = binding.web.getUrl(); if (referrer != null) headers.put("Referer", referrer); ((MainActivity) requireActivity()).showAddDownload(url, headers); }
    private void collectCandidate(String url, Map<String, String> requestHeaders) {
        if (!MediaDiscovery.isDirectMediaUrl(url)) return;
        if (mediaCandidates.size() >= MAX_MEDIA_CANDIDATES || mediaCandidates.containsKey(url)) return;
        mediaCandidates.put(url, new MediaCandidate(url, MediaDiscovery.label(url), requestHeaders));
        if (binding != null) updateMediaButton(binding.web.getUrl());
    }
    private void discoverDocumentMedia(WebView view) {
        String script = "(function(){try{const r=new Set();document.querySelectorAll('video,audio,source,a[download]').forEach(function(e){const u=e.currentSrc||e.src||e.href;if(u)r.add(u)});return JSON.stringify(Array.from(r).slice(0,50));}catch(e){return '[]';}})();";
        view.evaluateJavascript(script, encoded -> {
            if (binding == null || encoded == null) return;
            try {
                Object decoded = new JSONTokener(encoded).nextValue(); JSONArray values = new JSONArray(decoded instanceof String ? (String) decoded : "[]");
                for (int i = 0; i < values.length(); i++) collectCandidate(values.optString(i), Map.of());
            } catch (Exception ignored) { }
        });
    }
    private void updateMediaButton(String pageUrl) {
        if (binding == null) return; int count = mediaCandidates.size();
        boolean restrictedPage = MediaDiscovery.isRestrictedPlatformPage(pageUrl);
        binding.mediaDownload.setVisibility(count > 0 || restrictedPage ? View.VISIBLE : View.GONE);
        String description = count > 0 ? getResources().getQuantityString(R.plurals.downloadable_media_count, count, count) : getString(R.string.download_media);
        binding.mediaDownload.setContentDescription(description);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.mediaDownload, description);
    }
    private void chooseMedia() {
        MediaCandidate[] candidates = mediaCandidates.values().toArray(new MediaCandidate[0]);
        if (candidates.length == 0) { new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.no_direct_media_title).setMessage(R.string.no_direct_media_message).setPositiveButton(android.R.string.ok, null).show(); return; }
        if (candidates.length == 1) { download(candidates[0]); return; }
        BottomSheetMediaChooserBinding chooser = BottomSheetMediaChooserBinding.inflate(getLayoutInflater());
        chooser.mediaSummary.setText(getResources().getQuantityString(R.plurals.media_files_found, candidates.length, candidates.length));
        int margin = Math.round(6 * getResources().getDisplayMetrics().density);
        for (MediaCandidate candidate : candidates) {
            MaterialButton option = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            option.setText(candidate.label); option.setAllCaps(false); option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); option.setIconResource(R.drawable.ic_download); option.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); params.setMargins(0, margin, 0, margin); option.setLayoutParams(params);
            chooser.mediaOptions.addView(option);
        }
        ViewGroup.LayoutParams scrollParams = chooser.mediaListScroll.getLayoutParams(); scrollParams.height = Math.round(Math.min(320, candidates.length * 64) * getResources().getDisplayMetrics().density); chooser.mediaListScroll.setLayoutParams(scrollParams);
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext()); dialog.setContentView(chooser.getRoot());
        for (int i = 0; i < candidates.length; i++) { MediaCandidate candidate = candidates[i]; chooser.mediaOptions.getChildAt(i).setOnClickListener(v -> { dialog.dismiss(); download(candidate); }); }
        dialog.show();
    }
    private void download(MediaCandidate candidate) { intercept(candidate.url, binding.web.getSettings().getUserAgentString(), candidate.headers); }
    private void setAddressBarVisible(boolean visible) {
        if (binding == null || addressBarVisible == visible) return;
        addressBarVisible = visible;
        binding.showBrowserControls.animate().cancel();
        if (visible) binding.showBrowserControls.setVisibility(View.GONE);
        View[] controls = {binding.addressLayout, binding.go};
        for (View control : controls) {
            control.animate().cancel();
            if (visible) {
                control.setVisibility(View.VISIBLE); control.setAlpha(0f); control.setTranslationY(-control.getHeight() / 3f);
                control.animate().alpha(1f).translationY(0f).setDuration(160).start();
            } else {
                control.animate().alpha(0f).translationY(-control.getHeight() / 3f).setDuration(140).withEndAction(() -> {
                    if (!addressBarVisible) { control.setVisibility(View.GONE); control.setAlpha(1f); control.setTranslationY(0f); binding.showBrowserControls.setVisibility(View.VISIBLE); }
                }).start();
            }
        }
    }
    private static Map<String, String> allowedRequestHeaders(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) if (entry.getKey().equalsIgnoreCase("Accept") || entry.getKey().equalsIgnoreCase("Accept-Language") || entry.getKey().equalsIgnoreCase("Origin")) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private record MediaCandidate(String url, String label, Map<String, String> headers) {
            private MediaCandidate(String url, String label, Map<String, String> headers) {
                this.url = url;
                this.label = label;
                this.headers = Map.copyOf(headers);
            }
        }
    @Override public void onDestroyView() { binding.web.stopLoading(); binding.web.clearHistory(); binding.web.removeAllViews(); binding.web.destroy(); binding = null; super.onDestroyView(); }
}
