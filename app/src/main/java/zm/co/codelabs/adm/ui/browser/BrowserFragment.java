package zm.co.codelabs.adm.ui.browser;

import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.LinkedHashMap;
import java.util.Map;
import zm.co.codelabs.adm.databinding.FragmentBrowserBinding;
import zm.co.codelabs.adm.ui.MainActivity;
import zm.co.codelabs.adm.R;

public final class BrowserFragment extends Fragment {
    private FragmentBrowserBinding binding;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentBrowserBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        WebSettings settings = binding.web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSafeBrowsingEnabled(true); settings.setMediaPlaybackRequiresUserGesture(true);
        binding.web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { Uri uri = request.getUrl(); if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) return false; return true; }
            @Override public void onPageFinished(WebView view, String url) { binding.address.setText(url); }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { if (binding != null) { binding.web.removeAllViews(); binding.web.destroy(); com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), R.string.browser_render_failure, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show(); binding.getRoot().post(() -> getParentFragmentManager().beginTransaction().replace(zm.co.codelabs.adm.R.id.content, new BrowserFragment()).commitAllowingStateLoss()); } return true; }
        });
        binding.web.setWebChromeClient(new WebChromeClient() { @Override public void onProgressChanged(WebView view, int progress) { binding.loading.setVisibility(progress < 100 ? View.VISIBLE : View.GONE); binding.loading.setProgressCompat(progress, true); } });
        binding.web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> intercept(url, userAgent));
        binding.web.setOnLongClickListener(v -> { WebView.HitTestResult hit = binding.web.getHitTestResult(); String url = hit == null ? null : hit.getExtra(); if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) { intercept(url, settings.getUserAgentString()); return true; } return false; });
        binding.go.setOnClickListener(v -> navigate()); binding.address.setOnEditorActionListener((v, action, event) -> { if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) { navigate(); return true; } return false; });
    }
    private void navigate() { String value = binding.address.getText() == null ? "" : binding.address.getText().toString().trim(); if (!value.matches("(?i)^https?://.*")) value = "https://" + value; Uri uri = Uri.parse(value); if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) { binding.addressLayout.setError("Enter a valid web address"); return; } binding.addressLayout.setError(null); binding.web.loadUrl(uri.toString()); }
    private void intercept(String url, String userAgent) { Map<String, String> headers = new LinkedHashMap<>(); String cookies = CookieManager.getInstance().getCookie(url); if (cookies != null && !cookies.isBlank()) headers.put("Cookie", cookies); if (userAgent != null) headers.put("User-Agent", userAgent); String referrer = binding.web.getUrl(); if (referrer != null) headers.put("Referer", referrer); ((MainActivity) requireActivity()).showAddDownload(url, headers); }
    @Override public void onDestroyView() { binding.web.stopLoading(); binding.web.clearHistory(); binding.web.removeAllViews(); binding.web.destroy(); binding = null; super.onDestroyView(); }
}
