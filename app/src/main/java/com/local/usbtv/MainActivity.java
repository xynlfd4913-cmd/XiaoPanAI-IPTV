package com.local.usbtv;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int BUFFERING_TIMEOUT_MS = 12000;

    private final Handler handler = new Handler();
    private final List<Channel> channels = new ArrayList<>();

    private PlayerView playerView;
    private WebView webView;
    private ExoPlayer player;
    private TextView statusView;
    private LinearLayout panel;
    private ListView listView;
    private int channelIndex = 0;
    private int sourceIndex = 0;
    private int pendingChannelIndex = 0;

    private final Runnable hidePanel = new Runnable() {
        @Override
        public void run() {
            panel.setVisibility(View.GONE);
        }
    };

    private final Runnable bufferingTimeout = new Runnable() {
        @Override
        public void run() {
            tryNextSource();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        buildPlayer();
        loadChannels();
        bindChannels();

        if (channels.isEmpty()) {
            statusView.setText("没有频道");
            panel.setVisibility(View.VISIBLE);
            return;
        }

        playChannel(0);
        showPanelBriefly();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(false);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setVisibility(View.GONE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient());
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(20);
        statusView.setPadding(22, 14, 22, 14);
        statusView.setBackgroundColor(0xAA000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT
        );
        statusParams.setMargins(24, 24, 24, 24);
        root.addView(statusView, statusParams);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xDD111111);
        panel.setPadding(18, 18, 18, 18);

        TextView title = new TextView(this);
        title.setText("频道列表  上下选择 / OK播放");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 12);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        listView = new ListView(this);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        panel.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(440, FrameLayout.LayoutParams.MATCH_PARENT);
        panelParams.gravity = Gravity.LEFT;
        root.addView(panel, panelParams);
        setContentView(root);
    }

    private void buildPlayer() {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 XiaoPanAI/1.0")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    handler.removeCallbacks(bufferingTimeout);
                    statusView.setText(channelLabel());
                } else if (playbackState == Player.STATE_BUFFERING) {
                    handler.removeCallbacks(bufferingTimeout);
                    handler.postDelayed(bufferingTimeout, BUFFERING_TIMEOUT_MS);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                tryNextSource();
            }
        });
    }

    private void loadChannels() {
        Map<String, Channel> byName = new LinkedHashMap<>();
        try {
            InputStream input = getAssets().open("channels.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.contains("#genre#")) {
                    continue;
                }
                String[] parts = line.split(",", 2);
                if (parts.length != 2) {
                    continue;
                }
                String name = parts[0].trim();
                String url = parts[1].trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    continue;
                }
                Channel channel = byName.get(name);
                if (channel == null) {
                    channel = new Channel(name);
                    byName.put(name, channel);
                }
                channel.sources.add(url);
            }
            reader.close();
        } catch (Exception e) {
            statusView.setText("加载失败: " + e.getMessage());
        }
        channels.clear();
        channels.addAll(byName.values());
    }

    private void bindChannels() {
        List<String> names = new ArrayList<>();
        for (Channel channel : channels) {
            names.add(channel.name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, names);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                playChannel(position);
                panel.setVisibility(View.GONE);
            }
        });
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                pendingChannelIndex = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                pendingChannelIndex = channelIndex;
            }
        });
    }

    private void playChannel(int index) {
        if (channels.isEmpty()) {
            return;
        }
        channelIndex = wrap(index, channels.size());
        sourceIndex = 0;
        playCurrentSource();
    }

    private void playCurrentSource() {
        Channel channel = channels.get(channelIndex);
        if (channel.sources.isEmpty()) {
            tryNextChannel();
            return;
        }
        sourceIndex = wrap(sourceIndex, channel.sources.size());
        String url = channel.sources.get(sourceIndex);
        statusView.setText("正在播放 " + channelLabel());
        listView.setItemChecked(channelIndex, true);
        listView.setSelection(channelIndex);

        handler.removeCallbacks(bufferingTimeout);
        player.stop();
        if (isDirectStream(url)) {
            webView.stopLoading();
            webView.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.prepare();
            player.play();
            handler.postDelayed(bufferingTimeout, BUFFERING_TIMEOUT_MS);
        } else {
            playerView.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(url);
        }
    }

    private void tryNextSource() {
        if (channels.isEmpty()) {
            return;
        }
        handler.removeCallbacks(bufferingTimeout);
        Channel channel = channels.get(channelIndex);
        sourceIndex++;
        if (sourceIndex < channel.sources.size()) {
            statusView.setText("切换备用源 " + channel.name);
            playCurrentSource();
        } else {
            tryNextChannel();
        }
    }

    private void tryNextChannel() {
        channelIndex = wrap(channelIndex + 1, channels.size());
        sourceIndex = 0;
        playCurrentSource();
    }

    private String channelLabel() {
        Channel channel = channels.get(channelIndex);
        return (channelIndex + 1) + "/" + channels.size() + " " + channel.name;
    }

    private void showPanelBriefly() {
        panel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hidePanel);
        handler.postDelayed(hidePanel, 5000);
    }

    private void togglePanel() {
        if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            return;
        }
        panel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hidePanel);
        listView.requestFocus();
        listView.setSelection(channelIndex);
        pendingChannelIndex = channelIndex;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();
        boolean panelVisible = panel.getVisibility() == View.VISIBLE;

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (panelVisible) {
                int selected = listView.getSelectedItemPosition();
                if (selected == AdapterView.INVALID_POSITION) {
                    selected = pendingChannelIndex;
                }
                playChannel(selected);
                panel.setVisibility(View.GONE);
            } else {
                togglePanel();
            }
            return true;
        }

        if (panelVisible && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            return super.dispatchKeyEvent(event);
        }

        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) {
            playChannel(channelIndex - 1);
            panel.setVisibility(View.GONE);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            playChannel(channelIndex + 1);
            panel.setVisibility(View.GONE);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            playChannel(channelIndex - 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            playChannel(channelIndex + 1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isDirectStream(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".m3u8")
                || lower.contains(".mp4")
                || lower.contains(".flv")
                || lower.contains("play.m3u8")
                || lower.contains("playlist.m3u8");
    }

    private int wrap(int value, int size) {
        if (size <= 0) {
            return 0;
        }
        int result = value % size;
        return result < 0 ? result + size : result;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(hidePanel);
        handler.removeCallbacks(bufferingTimeout);
        if (player != null) {
            player.release();
            player = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    private static class Channel {
        final String name;
        final List<String> sources = new ArrayList<>();

        Channel(String name) {
            this.name = name;
        }
    }
}
