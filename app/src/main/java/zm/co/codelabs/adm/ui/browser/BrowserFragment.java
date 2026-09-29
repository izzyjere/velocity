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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import zm.co.codelabs.adm.databinding.BottomSheetMediaChooserBinding;
import zm.co.codelabs.adm.databinding.FragmentBrowserBinding;
import zm.co.codelabs.adm.ui.MainActivity;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.media.MediaDiscovery;
import zm.co.codelabs.adm.media.YouTubeExtractor;

public final class BrowserFragment extends Fragment {
    private static final String ARG_URL = "initial_url";
    private static final int MAX_MEDIA_CANDIDATES = 50;
    private static final String YOUTUBE_SESSION_SCRIPT =
            "(function(){try{" +
            "var p=window.ytInitialPlayerResponse||null;" +
            "if(!p&&window.ytplayer&&ytplayer.config&&ytplayer.config.args){" +
            "p=ytplayer.config.args.raw_player_response||ytplayer.config.args.player_response||null;}" +
            "var v='';if(window.ytcfg&&typeof ytcfg.get==='function'){v=ytcfg.get('VISITOR_DATA')||'';}" +
            "return JSON.stringify({playerResponse:typeof p==='string'?p:(p?JSON.stringify(p):''),visitorData:v});" +
            "}catch(e){return '{}';}})();";
    private FragmentBrowserBinding binding;
    private final LinkedHashMap<String, MediaCandidate> mediaCandidates = new LinkedHashMap<>();
    private boolean addressBarVisible = true;
    private int accumulatedScroll;
    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "youtube-extract"));
    private final YouTubeExtractor youTubeExtractor = new YouTubeExtractor();
    private boolean extracting;
    private String pendingUrl;
    public static BrowserFragment newInstance(String url) { BrowserFragment fragment = new BrowserFragment(); Bundle args = new Bundle(); args.putString(ARG_URL, url); fragment.setArguments(args); return fragment; }
    public void navigateTo(String url) {
        if (url == null || url.isBlank()) return;
        if (binding == null) { pendingUrl = url; return; }
        binding.address.setText(url);
        updateMediaButton(url);
        binding.web.loadUrl(url);
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentBrowserBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        WebSettings settings = binding.web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSafeBrowsingEnabled(true); settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager cookies = CookieManager.getInstance(); cookies.setAcceptCookie(true); cookies.setAcceptThirdPartyCookies(binding.web, true);
        binding.web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { Uri uri = request.getUrl(); if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) return false; return true; }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { mediaCandidates.clear(); if (binding != null) { setAddressBarVisible(true); binding.address.setText(url); updateMediaButton(url); } }
            @Override public void onPageFinished(WebView view, String url) { if (binding == null) return; binding.address.setText(url); discoverDocumentMedia(view); discoverPerformanceMedia(view); updateMediaButton(url); }
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
        ((MainActivity) requireActivity()).setBrowserControlsReveal(false, () -> { });
        binding.go.setOnClickListener(v -> navigate()); binding.address.setOnEditorActionListener((v, action, event) -> { if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) { navigate(); return true; } return false; });
        String initial = pendingUrl != null ? pendingUrl : getArguments() == null ? null : getArguments().getString(ARG_URL); pendingUrl = null; if (initial != null) { binding.address.setText(initial); updateMediaButton(initial); binding.web.loadUrl(initial); }
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
        evalJS(view, script);
    }

    private void evalJS(WebView view, String script) {
        view.evaluateJavascript(script, encoded -> {
            if (binding == null || encoded == null) return;
            try {
                Object decoded = new JSONTokener(encoded).nextValue(); JSONArray values = new JSONArray(decoded instanceof String ? (String) decoded : "[]");
                for (int i = 0; i < values.length(); i++) collectCandidate(values.optString(i), Map.of());
            } catch (Exception ignored) { }
        });
    }

    private void discoverPerformanceMedia(WebView view) {
        String script = "(function(){try{const r=new Set();performance.getEntriesByType('resource').forEach(function(e){if(e&&e.name)r.add(e.name)});return JSON.stringify(Array.from(r).slice(-200));}catch(e){return '[]';}})();";
        evalJS(view, script);
    }
    private void updateMediaButton(String pageUrl) {
        if (binding == null) return; int count = mediaCandidates.size();
        boolean mediaPage = MediaDiscovery.isMediaPageUrl(pageUrl);
        binding.mediaDownload.setVisibility(count > 0 || mediaPage ? View.VISIBLE : View.GONE);
        String description = count > 0 ? getResources().getQuantityString(R.plurals.downloadable_media_count, count, count) : getString(R.string.download_media);
        binding.mediaDownload.setContentDescription(description);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.mediaDownload, description);
    }
    private void chooseMedia() {
        String currentUrl = binding.web.getUrl();
        if (mediaCandidates.isEmpty() && MediaDiscovery.isYouTubeUrl(currentUrl) && YouTubeExtractor.videoId(currentUrl) != null) { resolveYouTube(currentUrl); return; }
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
    private void resolveYouTube(String pageUrl) {
        if (extracting) return;
        extracting = true;
        binding.loading.setVisibility(View.VISIBLE); binding.loading.setIndeterminate(true);
        String cookies = CookieManager.getInstance().getCookie(pageUrl);
        String userAgent = binding.web.getSettings().getUserAgentString();
        binding.web.evaluateJavascript(YOUTUBE_SESSION_SCRIPT, encoded -> {
            YouTubeExtractor.BrowserSession session = browserSession(cookies, userAgent, encoded);
            resolveYouTubeInBackground(pageUrl, session);
        });
    }
    private void resolveYouTubeInBackground(String pageUrl, YouTubeExtractor.BrowserSession session) {
        extractionExecutor.execute(() -> {
            YouTubeExtractor.Resolution resolution = null; String error = null;
            try { resolution = youTubeExtractor.resolve(pageUrl, session); } catch (Exception e) { error = e.getMessage(); }
            final YouTubeExtractor.Resolution result = resolution; final String failure = error;
            if (binding == null) return;
            binding.getRoot().post(() -> {
                extracting = false;
                if (binding == null) return;
                binding.loading.setIndeterminate(false); binding.loading.setVisibility(View.GONE);
                if (result == null) { new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.no_direct_media_title).setMessage(failure == null ? getString(R.string.no_direct_media_message) : failure).setPositiveButton(android.R.string.ok, null).show(); return; }
                showYouTubeStreams(result);
            });
        });
    }
    private static YouTubeExtractor.BrowserSession browserSession(String cookies, String userAgent,
                                                                   String encoded) {
        try {
            Object decoded = new JSONTokener(encoded == null ? "" : encoded).nextValue();
            String snapshot = decoded instanceof String ? (String) decoded : "{}";
            JSONObject values = new JSONObject(snapshot);
            return new YouTubeExtractor.BrowserSession(cookies,
                    values.optString("visitorData", null),
                    values.optString("playerResponse", null), userAgent);
        } catch (Exception ignored) {
            return new YouTubeExtractor.BrowserSession(cookies, null, null, userAgent);
        }
    }
    private void showYouTubeStreams(YouTubeExtractor.Resolution resolution) {
        List<YouTubeExtractor.Stream> streams = resolution.streams();
        if (streams.size() == 1) { downloadStream(streams.get(0), resolution.title(), youtubeStreamHeaders(streams.get(0), resolution.streamUserAgent())); return; }
        BottomSheetMediaChooserBinding chooser = BottomSheetMediaChooserBinding.inflate(getLayoutInflater());
        chooser.mediaSummary.setText(getResources().getQuantityString(R.plurals.media_files_found, streams.size(), streams.size()));
        int margin = Math.round(6 * getResources().getDisplayMetrics().density);
        for (YouTubeExtractor.Stream stream : streams) {
            MaterialButton option = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            option.setText(stream.label()); option.setAllCaps(false); option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); option.setIconResource(R.drawable.ic_download); option.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); params.setMargins(0, margin, 0, margin); option.setLayoutParams(params);
            chooser.mediaOptions.addView(option);
        }
        ViewGroup.LayoutParams scrollParams = chooser.mediaListScroll.getLayoutParams(); scrollParams.height = Math.round(Math.min(320, streams.size() * 64) * getResources().getDisplayMetrics().density); chooser.mediaListScroll.setLayoutParams(scrollParams);
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext()); dialog.setContentView(chooser.getRoot());
        for (int i = 0; i < streams.size(); i++) { YouTubeExtractor.Stream stream = streams.get(i); chooser.mediaOptions.getChildAt(i).setOnClickListener(v -> { dialog.dismiss(); downloadStream(stream, resolution.title(), youtubeStreamHeaders(stream, resolution.streamUserAgent())); }); }
        dialog.show();
    }
    private Map<String, String> youtubeStreamHeaders(YouTubeExtractor.Stream stream, String userAgent) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent == null || userAgent.isBlank() ? binding.web.getSettings().getUserAgentString() : userAgent);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Origin", "https://www.youtube.com");
        String referrer = binding.web.getUrl();
        if (referrer != null && !referrer.isBlank()) headers.put("Referer", referrer);
        String cookies = CookieManager.getInstance().getCookie(stream.url());
        if (cookies != null && !cookies.isBlank()) headers.put("Cookie", cookies);
        return headers;
    }
    private void downloadStream(YouTubeExtractor.Stream stream, String title, Map<String, String> headers) {
        ((MainActivity) requireActivity()).showAddDownload(stream.url(), headers, stream.suggestedFileName(title));
    }
    private void setAddressBarVisible(boolean visible) {
        if (binding == null || addressBarVisible == visible) return;
        addressBarVisible = visible;
        ((MainActivity) requireActivity()).setBrowserControlsReveal(!visible, () -> { accumulatedScroll = 0; setAddressBarVisible(true); });
        View[] controls = {binding.addressLayout, binding.go};
        for (View control : controls) {
            control.animate().cancel();
            if (visible) {
                control.setVisibility(View.VISIBLE); control.setAlpha(0f); control.setTranslationY(-control.getHeight() / 3f);
                control.animate().alpha(1f).translationY(0f).setDuration(160).start();
            } else {
                control.animate().alpha(0f).translationY(-control.getHeight() / 3f).setDuration(140).withEndAction(() -> {
                    if (!addressBarVisible) { control.setVisibility(View.GONE); control.setAlpha(1f); control.setTranslationY(0f); }
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
    @Override public void onDestroyView() { ((MainActivity) requireActivity()).setBrowserControlsReveal(false, () -> { }); extractionExecutor.shutdownNow(); binding.web.stopLoading(); binding.web.clearHistory(); binding.web.removeAllViews(); binding.web.destroy(); binding = null; super.onDestroyView(); }
}
