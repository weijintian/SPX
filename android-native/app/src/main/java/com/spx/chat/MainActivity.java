package com.spx.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaMetadataRetriever;
import android.media.ToneGenerator;
import android.view.MotionEvent;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class MainActivity extends Activity {
    private ChatCall chatCall;
    private AlertDialog callRecordRedialPrompt;
    private ChatCall calls() {
        if(chatCall!=null&&!chatCall.belongsTo(currentUserId)){chatCall.dispose();chatCall=null;}
        if(chatCall==null&&currentUserId>0&&!ApiClient.token(this).isEmpty()){
            chatCall=ChatCall.acquire(this,currentUserId,()->{invalidateChatSnapshot();syncChatHome();reconcileVisibleMessages();});
            chatCall.foreground(appInForeground);
        }
        return chatCall;
    }
    private static final long UPDATE_CHECK_INTERVAL_MS = 60_000L;
    private static final long PRESENCE_HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 4101;
    private static final int CAMERA_PERMISSION_REQUEST = 4102;
    private static final int RECORD_AUDIO_PERMISSION_REQUEST = 4103;
    private static final int PICK_SCAN_IMAGE = 4104;
    private static final int CAMERA_CHAT_PHOTO = 3001;
    private static final int CAMERA_CHAT_VIDEO = 3002;
    private static final int CAMERA_AVATAR = 3003;
    private static final String CHAT_NOTIFICATION_CHANNEL = "spx_chat_messages";
    private static final String RED_PACKET_NOTIFICATION_CHANNEL = "spx_red_packets_v1";
    private static final int BLUE = Color.rgb(45, 78, 207);
    private static final int TEXT = Color.rgb(24, 31, 51);
    private static final int MUTED = Color.rgb(103, 113, 135);
    private static final int BG = Color.rgb(246, 248, 252);
    private FrameLayout content;
    private View privacyShield;
    private TextView offlineBanner;
    private android.net.ConnectivityManager connectivityManager;
    private android.net.ConnectivityManager.NetworkCallback connectivityCallback;
    private final java.util.Map<String, Integer> unreadCursorByConversation = new java.util.HashMap<>();
    private int unreadDividerBefore = 0;
    private boolean unreadDividerInserted = false;
    private SeekBar activeVoiceBar;
    private final Handler voiceProgressHandler = new Handler(Looper.getMainLooper());
    private Runnable voiceProgressTick;
    private LinearLayout bottomNav;
    private int activeTab = 0;
    private boolean chatWindowOpen = false;
    private ListView activeChatListView;
    private LinearLayout activeChatRows;
    private ViewRowsAdapter activeChatRowsAdapter;
    private EditText activeChatSearchField;
    private int savedChatListPosition = 0;
    private int savedChatListOffset = 0;
    private String openedChatListSnapshot = "";
    private boolean restoreChatListPosition = false;
    private String renderedChatSnapshotSignature = "";
    private View chatHomePage;
    private int chatHomeUserId;
    private String chatHomeLanguage = "";
    private boolean chatHomeSyncing;
    private long chatSnapshotRevision;
    private TextView activeChatTitle;
    private boolean messageScrollTouched;
    private boolean askWindowOpen = false;
    private boolean subPageOpen = false;
    private boolean authChildOpen;
    private final AuthFormState authFormState = new AuthFormState();
    private final Handler authUiHandler = new Handler(Looper.getMainLooper());
    private boolean walletAssetDetailOpen = false;
    private boolean walletBalancesHidden = true;
    private int previousTab = 0;
    private String userName = "";
    private String userEmail = "";
    private String appLanguage = "zh-CN";
    private int currentUserId = 0;
    private View contactsPage;
    private Runnable contactsRender, contactsRefresh;
    private boolean contactsRefreshing;
    private long contactsRevision;
    private TextView activeChatStatus;
    private android.window.OnBackInvokedCallback systemBackCallback;
    private final Set<Dialog> accountDialogs=new HashSet<>();
    private long stakeDialogOpenedAt = 0;
    /** 金融类弹窗同一时间只允许一个，且 1.2 秒内不接受重复触发（防连点）。 */
    private boolean financialDialogBlocked() {
        for (Dialog dialogItem : accountDialogs) {
            if (dialogItem != null && dialogItem.isShowing()) return true;
        }
        return android.os.SystemClock.elapsedRealtime() - stakeDialogOpenedAt < 1200;
    }
    private void trackAccountDialog(Dialog dialog){accountDialogs.add(dialog);dialog.setOnDismissListener(closed->accountDialogs.remove(dialog));}
    private String currentUserSpxId = "";
    private String addressName = "";
    private String addressPhone = "";
    private String addressDetail = "";
    private String addressProvince = "福建省";
    private String addressCity = "泉州市";
    private String addressDistrict = "石狮市";
    private JSONArray areaData;
    private int productColumns = 2;
    private String mallSearchQuery = "";
    private String mallProductFilter = "all";
    private int mallProductsPage=1;
    private int mallProductsCount=0;
    private JSONArray[] mallLoadedPages;
    private MallCommerce commerce;
    private MallCommerce commerce(){if(commerce==null)commerce=new MallCommerce(this);return commerce;}
    private boolean showingCommerce;
    private boolean currentCommercePage;
    private View pendingProductLoading;
    private final java.util.ArrayDeque<PageFrame> pageHistory = new java.util.ArrayDeque<>();
    private final class PageFrame {
        final View view;
        final long financialRevision=AccountPageCache.revision(),savedAt=android.os.SystemClock.elapsedRealtime();
        final boolean sub = subPageOpen, chat = chatWindowOpen, ask = askWindowOpen, wallet = walletAssetDetailOpen, mall = currentCommercePage;
        final boolean groupCanManage = activeGroupCanManage, groupCanView = activeGroupCanViewMemberDetails;
        final int tab = activeTab, previous = previousTab, chatId = activeChatId, groupId = activeGroupId, peerId = activePeerUserId, detailGroupId = activeDetailGroupId;
        final String name = activeChatName, avatar = activeChatAvatar, c1 = activeChatC1, c2 = activeChatC2;
        PageFrame(View view) { this.view = view; }
    }
    private void rememberPage() {
        if (content == null || content.getChildCount() == 0) return;
        if (pageHistory.size() >= 24) pageHistory.removeLast();
        pageHistory.push(new PageFrame(content.getChildAt(0)));
    }
    private boolean restorePreviousPage() {
        if (pageHistory.isEmpty()) return false;
        PageFrame frame = pageHistory.pop();
        subPageOpen = frame.sub; askWindowOpen = frame.ask; walletAssetDetailOpen = frame.wallet;
        currentCommercePage = frame.mall; activeTab = frame.tab; previousTab = frame.previous;
        activeDetailGroupId = frame.detailGroupId;
        activeGroupCanManage = frame.groupCanManage;
        activeGroupCanViewMemberDetails = frame.groupCanView;
        if (frame.chat) {
            boolean needsRestart = activeChatId != frame.chatId || activeGroupId != frame.groupId;
            chatWindowOpen = true;
            if (needsRestart) {
                subPageOpen = false;
                openChatWindow(frame.chatId, frame.groupId, frame.name, frame.avatar, "", frame.c1, frame.c2, frame.peerId);
                subPageOpen = frame.sub;
                if (!frame.sub) return true;
            }
        } else chatWindowOpen = false;
        content.removeAllViews();
        content.addView(frame.view, new FrameLayout.LayoutParams(-1, -1));
        if(("wallet".equals(frame.view.getTag())||"staking".equals(frame.view.getTag())) && (frame.financialRevision!=AccountPageCache.revision()||android.os.SystemClock.elapsedRealtime()-frame.savedAt>=15000)){
            content.removeAllViews();if("wallet".equals(frame.view.getTag()))openWalletPage();else openStakingPage();return true;
        }
        if (frame.mall && commerce != null) commerce.resume();
        if(frame.view==contactsPage){if(contactsRender!=null)contactsRender.run();if(contactsRefresh!=null)contactsRefresh.run();}
        setBottomNavVisible(!frame.sub && !frame.chat && !frame.ask);
        if (frame.chat && activeMessageList != null && activeMessageScroll != null)
            scheduleChatHistoryRefresh(activeMessageList, activeMessageScroll, activeChatName, activeChatAvatar, activeChatC1, activeChatC2);
        return true;
    }
    void mallShow(LinearLayout root){showingCommerce=true;try{openSubPage(root,2);}finally{showingCommerce=false;}}
    void mallBeforeNavigation(){
        if(content.getChildCount()==0 || content.getChildAt(0)!=pendingProductLoading) rememberPage();
        pendingProductLoading=null;
    }
    void mallExit(){currentCommercePage=false;if(restorePreviousPage())return;subPageOpen=false;setBottomNavVisible(true);renderTab(activeTab);}
    int mallUserId(){return currentUserId;}
    void mallContact(JSONObject shop){openMerchantContact(shop);}
    void mallShop(int id){openShopPage(id);}
    void mallPin(LinearLayout body,View button){
        View scroll=(View)body.getParent();
        if(scroll.getParent() instanceof LinearLayout){body.removeView(button);LinearLayout root=(LinearLayout)scroll.getParent();LinearLayout bar=new LinearLayout(this);bar.setPadding(dp(14),dp(8),dp(14),dp(14));bar.setBackgroundColor(Color.WHITE);bar.addView(button,new LinearLayout.LayoutParams(-1,dp(52)));root.addView(bar);}
    }

    private Uri userAvatarUri;
    private Uri groupAvatarUri;
    private String activeGroupName = "";
    private Uri chatBackgroundUri;
    private int chatBackgroundPreset = 0;
    private int pendingPicker = 0;
    private SharedPreferences preferences;
    private boolean authRegisterMode = false;
    private boolean splashFinished = false;
    private final Handler splashHandler = new Handler(Looper.getMainLooper());
    private final Runnable enterAfterSplash = this::continueAfterSplash;
    private ChatWebSocket chatSocket;
    private int activeChatId = 0;
    private int activePeerUserId = 0;
    private int activeGroupId = 0;
    private int activeDetailGroupId = 0;
    private boolean activeGroupCanManage = false;
    private boolean activeGroupCanViewMemberDetails = true;
    private int lastMessageId = 0;
    private final ChatHistoryCursor historyCursor = new ChatHistoryCursor();
    private final Handler chatRefreshHandler = new Handler(Looper.getMainLooper());
    private final Handler readReceiptHandler = new Handler(Looper.getMainLooper());
    private Runnable chatHistoryRefresh;
    private Runnable pendingReadReceipt;
    private int chatSocketGeneration = 0;
    private int pendingMediaOwnerId;
    private int pendingMediaChatId = 0;
    private int pendingMediaGroupId = 0;
    private View pendingMediaBubble;
    private String pendingMediaClientId = "";
    private final Set<String> renderedMessageIds = new HashSet<>();
    private final Set<Integer> hiddenMessageIds = new HashSet<>();
    private final ArrayList<Integer> activeMentionUserIds = new ArrayList<>();
    private final Map<Integer,String> activeMentionNames = new HashMap<>();
    private boolean insertingMention;
    private Dialog mentionDialog;
    private final Map<String, View> renderedMessageViews = new HashMap<>();
    private LinearLayout activeMessageList;
    private ScrollView activeMessageScroll;
    private TextView newMessagesButton;
    private String activeChatName = "";
    private String activeChatAvatar = "";
    private String activeChatC1 = "#A8C0FF";
    private String activeChatC2 = "#3F5EFB";
    private JSONObject activeReplyMessage;
    private TextView activeReplyPreview;
    private final Map<String,JSONObject> activeMessageModels=new HashMap<>();
    private TextView olderMessagesButton;
    private boolean loadingOlderMessages, noOlderMessages, reconcilingMessages;
    private int chatViewEpoch;
    private int oldestHistoryCursor;
    private int pendingSearchMessageId;
    private boolean viewingHistoryContext;
    private long lastVisibleReconcile;
    private final Runnable trimChatWindow=()->trimRenderedMessages(false);
    private ChatOutbox chatOutbox;
    private final Map<String, View> pendingOutboxViews = new HashMap<>();
    private LinearLayout activeToolPanel;
    private final Handler draftHandler = new Handler(Looper.getMainLooper());
    private Runnable draftSave;
    private int notificationChatId, notificationGroupId, notificationUserId;
    private String notificationChatName = "";
    private boolean sendingMessage = false;
    private String pendingTextClientId="",pendingTextValue="",pendingTextTarget="";
    private String remoteAvatarUrl = "";
    private ToneGenerator messageTone;
    private View incomingMessageBanner;
    private final Handler incomingBannerHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideIncomingBanner = () -> {
        if (incomingMessageBanner != null && incomingMessageBanner.getParent() instanceof ViewGroup) {
            ((ViewGroup) incomingMessageBanner.getParent()).removeView(incomingMessageBanner);
        }
        incomingMessageBanner = null;
    };
    private boolean realtimeReady = false;
    private int pendingChatUnread = 0;
    private EditText activeMessageInput;
    private ChatCache chatCache;
    private String deviceId;
    private boolean appInForeground = true;
    private final Handler updateCheckHandler = new Handler(Looper.getMainLooper());
    private final Handler presenceHandler = new Handler(Looper.getMainLooper());
    private final Runnable presenceHeartbeat = new Runnable() {
        @Override public void run() {
            if (appInForeground && currentUserId > 0) {
                updatePresence(true);
                if(activeTab==1&&!subPageOpen&&contactsRefresh!=null)contactsRefresh.run();
                presenceHandler.postDelayed(this, PRESENCE_HEARTBEAT_INTERVAL_MS);
            }
        }
    };
    private final Runnable periodicUpdateCheck = new Runnable() {
        @Override public void run() {
            if (appInForeground) {
                checkAppUpdate(false);
                updateCheckHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS);
            }
        }
    };
    private int pendingCameraRequest;
    private Uri pendingCaptureUri;
    private MediaRecorder voiceRecorder;

    // 录音浮层
    private FrameLayout voiceOverlay;
    private VoiceWaveView voiceWave;
    private TextView voiceOverlayHint;
    private TextView voiceOverlayTimer;
    private boolean voiceOverlayCancelled = false;

    // 聊天多选
    private boolean selectingMessages = false;
    private final java.util.LinkedHashSet<Integer> selectedMessageIds = new java.util.LinkedHashSet<>();
    private LinearLayout selectionBar;
    private TextView selectionCountLabel;
    private MediaPlayer activeVoicePlayer;
    private TextView activeVoiceBubble;
    private String activeVoiceLabel = "";
    private File voiceRecordingFile;
    private long voiceRecordingStartedAt;
    private boolean voiceRecordingCancelled;
    private int pendingVoiceDurationSeconds;
    private boolean updateCheckInProgress = false;
    private boolean updatePromptShown = false;
    private AppUpdateDownload updateDownload;
    private final AppUpdateDownload.Listener updateDownloadListener = this::renderAppUpdateDownload;
    private TextView updateDownloadChip, updatePageStatus, updatePageButton;
    private ProgressBar updatePageProgress;
    private View updatePage;
    private JSONObject updatePageRelease;
    private AlertDialog appUpdatePrompt;
    private boolean manualUpdateCheckPending;
    private boolean waitingForInstallPermission = false;
    private JSONObject availableRelease;
    private File pendingUpdateApk;
    private int pendingSharedProductId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ApiClient.setSessionListener(sessionListener);
        preferences = getSharedPreferences("spx_profile", MODE_PRIVATE);
        updateDownload = AppUpdateDownload.get(this);
        appLanguage = resolveAppLanguage();
        updateDownload.addListener(updateDownloadListener);
        activeChatName = t("default_chat_name");
        activeChatAvatar = t("ui_chat_avatar");
        createChatNotificationChannel();
        chatCache = ChatCache.get(this);
        deviceId = preferences.getString("device_id", "");
        if (deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            preferences.edit().putString("device_id", deviceId).apply();
        }
        int savedUser=preferences.getInt("current_user_id",0);
        String savedServer=preferences.getString("current_account_server",ApiClient.baseUrl(this));
        loadOwnProfile(savedServer.equals(ApiClient.baseUrl(this)) && !ApiClient.token(this).isEmpty()?savedUser:0);
        installSystemBackHandler();
        if(savedInstanceState!=null){notificationUnreadOnly=savedInstanceState.getBoolean("notification_open_unread");notificationChatId=savedInstanceState.getInt("notification_chat_id");notificationGroupId=savedInstanceState.getInt("notification_group_id");notificationUserId=savedInstanceState.getInt("notification_user_id");notificationChatName=savedInstanceState.getString("notification_chat_name");}
        readChatNotification(getIntent());
        if (savedInstanceState == null) readSharedProduct(getIntent());
        else pendingSharedProductId = savedInstanceState.getInt("pending_shared_product_id", 0);
        // Configuration refresh must never hold the welcome screen open.
        ApiClient.refreshConfig(getApplicationContext(), null);
        if (savedInstanceState != null && savedInstanceState.getBoolean("splash_finished", false)) {
            continueAfterSplash();
        } else {
            showSplashPage();
        }
    }

    private void applySystemInsets(View root) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("pending_shared_product_id", pendingSharedProductId);
        outState.putBoolean("splash_finished", splashFinished);
        outState.putBoolean("notification_open_unread",notificationUnreadOnly);outState.putInt("notification_chat_id",notificationChatId);outState.putInt("notification_group_id",notificationGroupId);outState.putInt("notification_user_id",notificationUserId);outState.putString("notification_chat_name",notificationChatName);
        saveActiveChatDraft();
        if(commerce!=null)commerce.pause();
        super.onSaveInstanceState(outState);
    }

    private boolean validProductLink(Uri link) {
        if ("spx".equals(link.getScheme()) && "product".equals(link.getHost())) return true;
        return "https".equals(link.getScheme()) && "spx001.top".equals(link.getHost()) && "/spxchat/product-share.php".equals(link.getPath());
    }
    void mallJoinGroup(JSONObject group) {
        JSONObject req=new JSONObject();try {req.put("group_code",group.optString("group_code"));}catch(Exception e){return;}
        ApiClient.post(this,"groups.join",req,new ApiClient.Callback(){
            public void onSuccess(Object result){if(result instanceof JSONObject){commerce.externalPage();openChatWindow(0,((JSONObject)result).optInt("group_id"),group.optString("name","商品咨询群"),"群","group","#5B7CFA","#9B5DE5");}}
            public void onError(String message){toast(message);}
        });
    }
    private ChatExperience chatExperience;
    private int conversationFilter = 0;
    private ChatExperience chatTools() {
        if (chatExperience == null) chatExperience = new ChatExperience(this);
        return chatExperience;
    }
    private ChatSoundEffects chatSoundEffects;
    private ChatSoundEffects sounds(){if(chatSoundEffects==null)chatSoundEffects=new ChatSoundEffects(this);return chatSoundEffects;}
    private void playIncomingTone(JSONObject message){if(!appInForeground||voiceRecorder!=null||activeVoicePlayer!=null||ChatRedPacketNotice.parse(message)!=null)return;if("red_packet".equals(message.optString("message_type")))sounds().packet(message);else if(chatCall==null||!chatCall.hasSession())playMessageTone();}
    private ChatRedPacket chatRedPacket;
    private ChatRedPacket redPackets(){if(chatRedPacket==null)chatRedPacket=new ChatRedPacket(this);return chatRedPacket;}
    void redPacketClaimed(String id){
        if(appInForeground&&voiceRecorder==null&&activeVoicePlayer==null)
            sounds().claimed(ApiClient.baseUrl(this)+":"+currentUserId+":"+id);
    }
    void redPacketWallet(){openWalletPage();}
    void redPacketReceipt(JSONObject packet){
        JSONObject message=packet.optJSONObject("claim_notice");ChatRedPacketNotice notice=ChatRedPacketNotice.parse(message);
        if(notice==null||notice.receiverId!=currentUserId||!notice.packetId.equals(packet.optString("id"))||!ChatRedPacket.positiveClaim(packet)||message.optInt("id")<=0)return;
        int group=message.optInt("group_id"),chat=message.optInt("chat_id");
        if((group>0)==(chat>0)||!(group>0?"group":"chat").equals(packet.optString("scope")))return;
        final String scope=group>0?"group":"chat";final int target=group>0?group:chat;
        chatCache.execute(()->chatCache.put(scope,target,message));invalidateChatSnapshot();
        if(chatWindowOpen&&activeChatId==chat&&activeGroupId==group&&activeMessageList!=null){boolean follow=isMessageScrollAtBottom();renderChatMessage(message,activeMessageList,activeMessageScroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2);if(follow&&!viewingHistoryContext)scrollMessagesToBottom();}
    }
    void redPacketShow(LinearLayout page){openSubPage(page,activeTab);}
    boolean redPacketVisible(View page){return content!=null&&content.getChildCount()>0&&content.getChildAt(0)==page;}
    void redPacketClose(View page){if(redPacketVisible(page)){android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(page.getWindowToken(),0);closeSubPage();}}
    void redPacketSent(int chat,int group,JSONObject message){
        if(voiceRecorder==null&&activeVoicePlayer==null)sounds().packet(message);
        try{message.put("sender_id",currentUserId).put("nickname",userName).put("avatar_url",remoteAvatarUrl);}catch(Exception ignored){}
        final String scope=group>0?"group":"chat";final int target=group>0?group:chat;
        chatCache.execute(()->chatCache.put(scope,target,message));invalidateChatSnapshot();
        if(chatWindowOpen&&activeChatId==chat&&activeGroupId==group&&activeMessageList!=null){renderChatMessage(message,activeMessageList,activeMessageScroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2);if(!viewingHistoryContext)scrollMessagesToBottom();}
    }
    private View addRedPacketCard(LinearLayout parent,boolean mine,String sender,String avatarUrl,String raw){
        LinearLayout line=row();line.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);
        if(!mine)line.addView(avatarWithRemoteImage(groupAvatarInitial(sender),avatarUrl,"#A8C0FF","#3F5EFB",36));
        View card=redPackets().card(raw,mine);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Math.min(dp(250),getResources().getDisplayMetrics().widthPixels-dp(108)),-2);p.setMargins(mine?dp(44):dp(10),dp(6),0,dp(6));line.addView(card,p);parent.addView(line,new LinearLayout.LayoutParams(-1,-2));return card;
    }
    void mallShareProduct(JSONObject product) { chatTools().shareProduct(product); }
    private void productMessageLinks(TextView view,String content) { ChatLinks.bind(this,view); }
    private String productPreview(String content) {
        try { return "[商品] " + new JSONObject(content).optString("name","商品卡片"); } catch(Exception ignored) { return "[商品卡片]"; }
    }
    /** 购物助手卡片：商品、金额、商家、脱敏买家。 */
    private View addMallOrderCard(LinearLayout parent,boolean mine,String sender,String avatarUrl,JSONObject message){
        JSONObject data=message.optJSONObject("payload");
        if(data==null){try{data=new JSONObject(message.optString("payload_json","{}"));}catch(Exception e){data=null;}}
        LinearLayout line=row();line.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);
        if(!mine)line.addView(avatarWithRemoteImage(sender.isEmpty()?"购":sender.substring(0,1),avatarUrl,"#3E7BFF","#1D4FCB",36));
        LinearLayout card=column();card.setPadding(dp(12),dp(12),dp(12),dp(10));
        card.setBackground(stroke(Color.WHITE,Color.rgb(225,231,242),16,1));
        TextView head=bodyText("🛒 新订单",13,BLUE);head.setTypeface(null,1);card.addView(head);
        String product=data==null?"":data.optString("product","");
        TextView name=bodyText(product.isEmpty()?message.optString("content","新订单播报"):product,15,TEXT);
        name.setTypeface(null,1);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0,dp(6),0,0);card.addView(name);
        String variant=data==null?"":data.optString("variant","");
        if(!variant.isEmpty()){TextView v=bodyText(variant,12,MUTED);v.setPadding(0,dp(4),0,0);card.addView(v);}
        String amount=data==null?"":data.optString("amount","");
        if(!amount.isEmpty()){TextView price=bodyText(MallMoney.show(amount)+" SPX001",16,BLUE);price.setTypeface(null,1);price.setPadding(0,dp(8),0,0);card.addView(price);}
        String merchant=data==null?"":data.optString("merchant","");
        String buyer=data==null?"":data.optString("buyer","");
        if(!merchant.isEmpty()){TextView t=bodyText("商家 "+merchant,11,MUTED);t.setPadding(0,dp(6),0,0);card.addView(t);}
        if(!buyer.isEmpty()){TextView t=bodyText("买家 "+buyer,11,MUTED);t.setPadding(0,dp(2),0,0);card.addView(t);}
        View rule=new View(this);rule.setBackgroundColor(Color.rgb(238,241,246));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(1));rp.topMargin=dp(10);card.addView(rule,rp);
        TextView foot=bodyText("SPX 购物助手 · 实时播报",10,MUTED);foot.setPadding(0,dp(8),0,0);card.addView(foot);
        int width=Math.min(dp(252),(int)(getResources().getDisplayMetrics().widthPixels*0.66));
        line.addView(card,new LinearLayout.LayoutParams(width,-2));
        parent.addView(line);
        return line;
    }

    private View addProductCard(LinearLayout parent,boolean mine,String sender,String avatarUrl,String raw) {
        JSONObject data;try { data=new JSONObject(raw); } catch(Exception e){return addMessage(parent,sender,"商","#A8C0FF","#3F5EFB","商品卡片暂不可用",mine,avatarUrl);}
        final int id=data.optInt("product_id");
        LinearLayout line=row();line.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);
        if(!mine)line.addView(avatarWithRemoteImage(sender.isEmpty()?"商":sender.substring(0,1),avatarUrl,"#A8C0FF","#3F5EFB",36));
        LinearLayout card=column();card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(stroke(Color.WHITE,Color.rgb(225,231,242),20,1));
        LinearLayout product=row();product.setGravity(Gravity.TOP);
        ImageView picture=new ImageView(this);picture.setScaleType(ImageView.ScaleType.CENTER_CROP);picture.setBackground(round(Color.rgb(238,243,255),12));picture.setClipToOutline(true);picture.setContentDescription(data.optString("name","商品图片"));product.addView(picture,new LinearLayout.LayoutParams(dp(74),dp(74)));MallImages.bind(picture,data.optString("image_url"),false);
        LinearLayout info=column();info.setPadding(dp(12),0,0,0);TextView name=bodyText(data.optString("name","商品"),15,TEXT);name.setTypeface(null,1);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(name);
        String cny=data.isNull("cny_price")?"":data.optString("cny_price","");
        TextView price=bodyText(cny.isEmpty()?MallMoney.show(data.optString("price","0"))+" SPX001":"¥"+MallMoney.show(cny)+" CNY",15,BLUE);price.setTypeface(null,1);price.setPadding(0,dp(8),0,0);info.addView(price);
        product.addView(info,new LinearLayout.LayoutParams(0,-2,1));card.addView(product);
        TextView shop=bodyText(data.optString("shop_name","SPX 好物商城"),11,MUTED);shop.setSingleLine();shop.setEllipsize(android.text.TextUtils.TruncateAt.END);shop.setPadding(0,dp(12),0,dp(8));card.addView(shop);
        View rule=new View(this);rule.setBackgroundColor(Color.rgb(235,239,247));card.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));TextView action=bodyText("查看商品详情  ›",13,BLUE);action.setPadding(0,dp(9),0,0);card.addView(action);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Math.min(dp(285),getResources().getDisplayMetrics().widthPixels-dp(108)),-2);lp.setMargins(mine?dp(48):dp(10),dp(6),0,dp(6));line.addView(card,lp);parent.addView(line,new LinearLayout.LayoutParams(-1,-2));
        card.setOnClickListener(v->{if(id>0){openProductDetail(id,"","","","");}else toast("商品已不可用");});return card;
    }

    private void readSharedProduct(Intent intent) {
        Uri link = intent == null ? null : intent.getData();
        if (link == null || !validProductLink(link)) return;
        try { int id = Integer.parseInt(link.getQueryParameter("id")); if (id > 0) pendingSharedProductId = id; } catch (Exception ignored) { }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); readSharedProduct(intent); readChatNotification(intent);
        if(CallService.OPEN.equals(intent.getAction())&&calls()!=null)calls().openFromNotification(intent.getStringExtra("call_session"));
        if(bottomNav!=null&&!ApiClient.token(this).isEmpty())openPendingChatNotification();
        if (bottomNav != null && !ApiClient.token(this).isEmpty()) openPendingSharedProduct();
    }
    private boolean notificationUnreadOnly;
    private void readChatNotification(Intent intent) {
        if(intent==null||!intent.hasExtra("notification_user_id"))return;
        notificationUnreadOnly=intent.getBooleanExtra("notification_open_unread",false);
        notificationChatId=Math.max(0,intent.getIntExtra("notification_chat_id",0)); notificationGroupId=Math.max(0,intent.getIntExtra("notification_group_id",0));
        notificationUserId=intent.getIntExtra("notification_user_id",0); notificationChatName=intent.getStringExtra("notification_chat_name"); intent.removeExtra("notification_user_id");
    }
    private void openPendingChatNotification() {
        if((notificationChatId<=0&&notificationGroupId<=0&&!notificationUnreadOnly)||ApiClient.token(this).isEmpty()||bottomNav==null)return;
        boolean unreadOnly=notificationUnreadOnly;notificationUnreadOnly=false;
        int chat=notificationChatId,group=notificationGroupId,account=notificationUserId;String name=notificationChatName;notificationChatId=notificationGroupId=0;
        if(account!=currentUserId){toast("这条通知属于其他登录账号");return;}
        if(group>0){name="群聊";JSONArray rows=readChatSnapshot();for(int i=0;i<rows.length();i++){JSONObject c=rows.optJSONObject(i);if(c!=null&&"group".equals(c.optString("kind"))&&c.optInt("id")==group){name=c.optString("title",c.optString("name","群聊"));break;}}}
        if(chatWindowOpen)closeChatWindow();if(commerce!=null)commerce.reset();if(subPageOpen)closeSubPage();switchTab(0);
        if(unreadOnly)return;
        openChatWindow(chat,group,name==null||name.isEmpty()?"聊天":name,group>0?"群":"聊",group>0?"group":"online","#A8C0FF","#3F5EFB");
    }

    private void openPendingSharedProduct() {
        if (pendingSharedProductId <= 0 ||  ApiClient.token(this).isEmpty()) return;
        int id = pendingSharedProductId; pendingSharedProductId = 0;
        if (chatWindowOpen) closeChatWindow();
        if (commerce != null) commerce.reset();
        openProductDetail(id, "", "", "", "");
    }

    private void showSplashPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        ImageView splash = new ImageView(this);
        splash.setImageResource(com.spx.chat.R.drawable.spx_im_splash);
        splash.setScaleType(ImageView.ScaleType.FIT_XY);
        splash.setContentDescription(t("ui_splash_desc"));
        splash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        // The artwork contains the label; overlay a real, accessible button.
        android.widget.Button enter = new android.widget.Button(this);
        enter.setText("");
        enter.setContentDescription(t("ui_enter_app"));
        enter.setBackgroundColor(Color.TRANSPARENT);
        enter.setOnClickListener(v -> continueAfterSplash());
        root.addView(enter, new FrameLayout.LayoutParams(1, 1));
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left, height = bottom - top;
            if (width <= 0 || height <= 0 || (width == oldRight - oldLeft && height == oldBottom - oldTop)) return;
            FrameLayout.LayoutParams button = new FrameLayout.LayoutParams(Math.round(width * 0.58f), Math.round(height * 0.066f));
            button.leftMargin = Math.round(width * 0.21f);
            button.topMargin = Math.round(height * 0.827f);
            enter.setLayoutParams(button);
        });
        applySystemInsets(root);
        setContentView(root);
        splashHandler.postDelayed(enterAfterSplash, 2000);
    }

    private void continueAfterSplash() {
        if (splashFinished || isFinishing() || isDestroyed()) return;
        splashFinished = true;
        splashHandler.removeCallbacks(enterAfterSplash);
        if (ApiClient.token(this).isEmpty()) {
            showAuthenticationPage();
        } else {
            buildApp();
            final String startupToken = ApiClient.token(this);
            ApiClient.get(this, "me", null, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (isFinishing() || isDestroyed() || !startupToken.equals(ApiClient.token(MainActivity.this))) return;
                    if (data instanceof JSONObject) {
                        JSONObject user = (JSONObject) data;
                        acceptAuthenticatedUser(user);
                        syncDeviceInfo();
                    }
                }
                @Override public void onError(String message) {
                    // Keep the cached session on a timeout or offline startup.
                }
                @Override public void onHttpError(int status, String message) {
                    if (status != 401 || isFinishing() || isDestroyed() || !startupToken.equals(ApiClient.token(MainActivity.this))) return;
                    ApiClient.clearToken(MainActivity.this);
                    showAuthenticationPage();
                }
            });
        }
    }

    @Override protected void onDestroy() {
        ApiClient.removeSessionListener(sessionListener);
        if(Build.VERSION.SDK_INT>=33 && systemBackCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBackCallback);
        if(chatMediaViewer!=null)chatMediaViewer.dismiss();
        for(Dialog dialog:new ArrayList<>(accountDialogs))dialog.dismiss();accountDialogs.clear();
        if(commerce!=null)commerce.dispose();
        if (updateDownload != null) updateDownload.removeListener(updateDownloadListener);
        if (appUpdatePrompt != null) appUpdatePrompt.dismiss();
        if (callRecordRedialPrompt != null) callRecordRedialPrompt.dismiss();
        AppNotice.stop(this);
        if(chatCall!=null){chatCall.detach(this);chatCall=null;}
        if(chatRedPacket!=null)chatRedPacket.dispose();
        saveActiveChatDraft();if(chatOutbox!=null)chatOutbox.stop();
        // Every repeating Runnable must be released here, otherwise queued messages keep the
        // Activity reachable for up to a minute after it is gone.
        draftHandler.removeCallbacksAndMessages(null);
        authUiHandler.removeCallbacksAndMessages(null);
        authFormState.clearDrafts();
        splashHandler.removeCallbacksAndMessages(null);
        chatRefreshHandler.removeCallbacksAndMessages(null);
        readReceiptHandler.removeCallbacksAndMessages(null);
        incomingBannerHandler.removeCallbacksAndMessages(null);
        updateCheckHandler.removeCallbacksAndMessages(null);
        presenceHandler.removeCallbacksAndMessages(null);
        chatSocketGeneration++;
        if (chatSocket != null) { chatSocket.close(); chatSocket = null; }
        super.onDestroy();
    }

    private void showAuthLoading() {
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(BG);
        page.addView(bodyText(t("app_loading"), 15, MUTED));
        applySystemInsets(page);
        setContentView(page);
    }

    private final ApiClient.SessionListener sessionListener = this::handleSessionExpired;

    private void handleSessionExpired(String message) {
        if(isFinishing()||isDestroyed())return;
        presenceHandler.removeCallbacks(presenceHeartbeat);
        chatRefreshHandler.removeCallbacksAndMessages(null);
        stopActiveVoicePlayback();
        if(chatSoundEffects!=null)chatSoundEffects.stop();
        if(chatMediaViewer!=null)chatMediaViewer.dismiss();
        chatHomePage=null;activeChatRows=null;activeChatRowsAdapter=null;activeChatSearchField=null;
        chatHomeUserId=0;chatSnapshotRevision++;renderedChatSnapshotSignature="";
        showAuthenticationPage();
        currentUserId=0;
        toast(message==null||message.isEmpty()?"登录已失效，请重新登录":message);
    }



    private void showAuthenticationPage() {
        if(callRecordRedialPrompt!=null){callRecordRedialPrompt.dismiss();callRecordRedialPrompt=null;}
        if(chatCall!=null){chatCall.dispose();chatCall=null;}
        if(chatRedPacket!=null)chatRedPacket.dispose();
        saveActiveChatDraft();if(chatOutbox!=null){chatOutbox.stop();chatOutbox=null;}
        closeChatSocket();if(chatSocket!=null){chatSocket.close();chatSocket=null;}chatSocketGeneration++;realtimeReady=false;
        resetAccountSurface();
        bottomNav = null;
        chatWindowOpen = false;
        askWindowOpen = false;
        subPageOpen = false;
        renderAuthenticationPage();
        checkAppUpdate(false);
    }

    private void renderAuthenticationPage() {
        beginAuthPage();
        authChildOpen=false;
        ChatBadge.clear(this);
        pageHistory.clear();
        currentCommercePage = false;
        pendingProductLoading = null;
        if (commerce != null) commerce.reset();
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = column();
        page.setPadding(dp(28), dp(42), dp(28), dp(36));

        TextView brand = bodyText("SPX IM", 17, BLUE);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(brand, new LinearLayout.LayoutParams(-1, dp(34)));
        TextView title = bodyText(t(authRegisterMode ? "create_account" : "welcome_back"), 30, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(48));
        titleParams.setMargins(0, dp(26), 0, 0);
        page.addView(title, titleParams);
        page.addView(bodyText(t(authRegisterMode ? "register_subtitle" : "login_subtitle"), 14, MUTED));

        LinearLayout tabs = row();
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(round(Color.rgb(232, 236, 244), 14));
        TextView loginTab = centeredText(t("login"), 15, authRegisterMode ? MUTED : TEXT);
        TextView registerTab = centeredText(t("register"), 15, authRegisterMode ? Color.WHITE : MUTED);
        loginTab.setBackground(authRegisterMode ? round(Color.TRANSPARENT, 11) : round(Color.WHITE, 11));
        registerTab.setBackground(authRegisterMode ? round(BLUE, 11) : round(Color.TRANSPARENT, 11));
        tabs.addView(loginTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        tabs.addView(registerTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, dp(52));
        tabsParams.setMargins(0, dp(28), 0, dp(24));
        page.addView(tabs, tabsParams);
        loginTab.setOnClickListener(v -> { if (canLeaveAuth() && authRegisterMode) { authRegisterMode = false; renderAuthenticationPage(); } });
        registerTab.setOnClickListener(v -> { if (canLeaveAuth() && !authRegisterMode) { authRegisterMode = true; renderAuthenticationPage(); } });

        TextView status = bodyText("", 13, Color.rgb(207, 67, 67));
        if (authRegisterMode) buildRegisterForm(page, status);
        else buildLoginForm(page, status);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        applySystemInsets(root);
        setContentView(root);
    }

    private void buildLoginForm(LinearLayout page, TextView status) {
        EditText account = authInput("login.account", t("username_or_email"), 1);
        account.setSingleLine(true);
        EditText password = authInput("login.password", t("password"), 129);
        password.setInputType(129);
        page.addView(account);
        page.addView(password);
        addAuthStatus(page, status);
        TextView submit = authPrimaryButton(t("login"));
        page.addView(submit, authButtonParams());
        submit.setOnClickListener(v -> {
            String login = account.getText().toString().trim();
            String secret = password.getText().toString();
            if (login.isEmpty() || secret.isEmpty()) { showAuthError(status, t("login_fields_required")); return; }
            if (!beginAuthSubmit(submit, "auth_logging_in")) return;
            JSONObject body = new JSONObject();
            try { body.put("username", login); body.put("password", secret); addDeviceInfo(body); } catch (Exception ignored) { }
            ApiClient.post(this, "auth.login", body, authCallback(submit, status));
        });
        TextView forgot = centeredText(t("forgot_password"), 14, BLUE);
        forgot.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        forgot.setOnClickListener(v -> { if (canLeaveAuth()) openForgotPasswordPage(); });
        page.addView(forgot, new LinearLayout.LayoutParams(-1, dp(42)));
        password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        password.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            if (submit.isEnabled()) submit.performClick(); return true;
        });
    }

    private void openForgotPasswordPage() {
        beginAuthPage();
        authChildOpen=true;
        LinearLayout root = subPageRoot(t("forgot_password_title"), t("forgot_password_subtitle"));
        LinearLayout body = column();
        body.setPadding(dp(20), dp(24), dp(20), dp(20));
        EditText email = authInput("reset.email", t("registered_email"), 33);
        email.setInputType(33);
        EditText code = authInput("reset.code", t("email_code"), 2);
        code.setInputType(2);
        LinearLayout codeRow = row();
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        codeRow.addView(code, new LinearLayout.LayoutParams(0, dp(56), 1));
        TextView sendCode = centeredText(t("get_code"), 13, BLUE);
        sendCode.setBackground(round(Color.rgb(238, 242, 255), 12));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(112), dp(56));
        codeParams.setMargins(dp(8), 0, 0, 0);
        codeRow.addView(sendCode, codeParams);
        EditText newPassword = authInput("reset.password", t("new_password"), 129);
        newPassword.setInputType(129);
        TextView status = bodyText("", 13, Color.rgb(207, 67, 67));
        body.addView(email);
        LinearLayout.LayoutParams codeRowParams = new LinearLayout.LayoutParams(-1, dp(56));
        codeRowParams.bottomMargin = dp(12);
        body.addView(codeRow, codeRowParams);
        body.addView(newPassword);
        body.addView(status, new LinearLayout.LayoutParams(-1, -2));
        bindAuthCode(email, code, "reset", "password_reset", sendCode, status);
        TextView submit = centeredText(t("reset_password"), 15, Color.WHITE);
        submit.setBackground(round(BLUE, 14));
        submit.setOnClickListener(v -> {
            String address = email.getText().toString().trim();
            String verificationCode = code.getText().toString().trim();
            String secret = newPassword.getText().toString();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { showAuthError(status,t("email_invalid")); return; }
            if (!AuthFormState.validCode(verificationCode)) { showAuthError(status,t("code_invalid")); return; }
            if (secret.length() < 6) { showAuthError(status,t("password_short")); return; }
            if (!beginAuthSubmit(submit, "auth_resetting")) return;
            final int pageID = authFormState.page();
            JSONObject request = new JSONObject();
            try { request.put("email", address); request.put("code", verificationCode); request.put("new_password", secret); } catch (Exception ignored) { authFormState.finishSubmit(); submit.setEnabled(true); submit.setText(t("reset_password")); return; }
            ApiClient.post(this, "auth.password.reset", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (!authPageCurrent(pageID, submit)) return;
                    authFormState.finishSubmit(); authFormState.clearDrafts();
                    authFormState.draft("login.account", address);
                    toast(t("password_reset_login")); authRegisterMode = false; showAuthenticationPage();
                }
                @Override public void onError(String message) {
                    if (!authPageCurrent(pageID, submit)) return;
                    authFormState.finishSubmit(); submit.setEnabled(true); submit.setText(t("reset_password")); showAuthError(status,message);
                }
            });
        });
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(-1, dp(50));
        submitParams.setMargins(0, dp(8), 0, 0);
        body.addView(submit, submitParams);
        ScrollView resetScroll = new ScrollView(this); resetScroll.setFillViewport(true);
        resetScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(resetScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        applySystemInsets(root);
        setContentView(root);
    }

    private void buildRegisterForm(LinearLayout page, TextView status) {
        EditText email = authInput("register.email", t("email_address"), 33);
        email.setInputType(33);
        email.setSingleLine(true);
        EditText code = authInput("register.code", t("email_code"), 2);
        code.setInputType(2);
        LinearLayout codeRow = row();
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        codeRow.addView(code, new LinearLayout.LayoutParams(0, dp(56), 1));
        TextView sendCode = centeredText(t("send_code"), 13, BLUE);
        sendCode.setBackground(stroke(Color.WHITE, Color.rgb(211, 220, 239), 12, 1));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(112), dp(56));
        sendParams.setMargins(dp(8), 0, 0, 0);
        codeRow.addView(sendCode, sendParams);
        EditText username = authInput("register.username", t("set_username"), 1);
        username.setSingleLine(true);
        EditText password = authInput("register.password", t("set_password"), 129);
        password.setInputType(129);
        EditText invite = authInput("register.invite", t("invite_spx_id_optional"), 1);
        invite.setSingleLine(true);
        page.addView(email);
        LinearLayout.LayoutParams codeRowParams = new LinearLayout.LayoutParams(-1, dp(56));
        codeRowParams.bottomMargin = dp(12);
        page.addView(codeRow, codeRowParams);
        page.addView(username);
        page.addView(password);
        page.addView(invite);

        bindAuthCode(email, code, "register", "register", sendCode, status);

        addAuthStatus(page, status);
        TextView submit = authPrimaryButton(t("verify_register"));
        page.addView(submit, authButtonParams());
        submit.setOnClickListener(v -> {
            String address = email.getText().toString().trim();
            String verifyCode = code.getText().toString().trim();
            String account = username.getText().toString().trim();
            String secret = password.getText().toString();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { showAuthError(status, t("email_invalid")); return; }
            if (!AuthFormState.validCode(verifyCode)) { showAuthError(status, t("email_code_invalid")); return; }
            if (account.isEmpty()) { showAuthError(status, t("username_required")); return; }
            if (secret.length() < 6) { showAuthError(status, t("password_short")); return; }
            if (!beginAuthSubmit(submit, "auth_registering")) return;
            JSONObject body = new JSONObject();
            try {
                body.put("email", address);
                body.put("code", verifyCode);
                body.put("username", account);
                body.put("password", secret);
                addDeviceInfo(body);
                String inviteCode = invite.getText().toString().trim();
                if (!inviteCode.isEmpty()) body.put("invite_code", inviteCode);
            } catch (Exception ignored) { }
            ApiClient.post(this, "auth.register", body, authCallback(submit, status));
        });
    }

    private void beginAuthPage() {
        authFormState.nextPage(); authFormState.finishSubmit();
        authUiHandler.removeCallbacksAndMessages(null);
    }

    private boolean authPageCurrent(int page, View view) {
        return page == authFormState.page() && !isFinishing() && !isDestroyed() && view.isAttachedToWindow();
    }

    private boolean canLeaveAuth() {
        if (!authFormState.submitting() && !authFormState.hasPendingCodeRequest()) return true;
        toast(authFormState.hasPendingCodeRequest() ? t("auth_wait_code_result") : t("auth_wait_result"));
        return false;
    }

    private EditText authInput(String key, String hint, int type) {
        EditText input = formInput(hint, authFormState.draft(key));
        input.setInputType(type); input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        if (key.endsWith(".code")) {
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
            input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789"));
        }
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { authFormState.draft(key, s.toString()); }
            public void afterTextChanged(android.text.Editable value) { }
        });
        return input;
    }

    private boolean beginAuthSubmit(TextView submit, String label) {
        if (authFormState.hasPendingCodeRequest()) { toast(t("auth_wait_code_result")); return false; }
        if (!authFormState.beginSubmit()) return false;
        submit.setEnabled(false); submit.setText(t(label));
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(submit.getWindowToken(), 0);
        return true;
    }

    private void bindAuthCode(EditText email, EditText code, String draft, String purpose, TextView button, TextView status) {
        final int page = authFormState.page();
        final boolean[] requestPending = {false};
        Runnable refresh = () -> {
            String address = email.getText().toString();
            int remaining = authFormState.remaining(address, purpose);
            boolean sending = requestPending[0] || authFormState.sending(address, purpose);
            email.setEnabled(!requestPending[0]);
            button.setText(sending ? t("auth_sending_code") : remaining > 0 ? remaining + " " + t("seconds_retry") : t("send_code"));
            button.setEnabled(!authFormState.submitting() && !sending && remaining == 0);
        };
        Runnable tick = new Runnable() {
            public void run() {
                if (page != authFormState.page() || isFinishing() || isDestroyed()) return;
                if (button.isAttachedToWindow()) refresh.run();
                authUiHandler.postDelayed(this, 1000);
            }
        };
        refresh.run(); authUiHandler.postDelayed(tick, 1000);
        email.addTextChangedListener(new android.text.TextWatcher() {
            private String previous = AuthFormState.address(email.getText().toString());
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String address = AuthFormState.address(s.toString());
                if (!address.equals(previous)) { code.setText(""); status.setText(""); previous = address; }
                refresh.run();
            }
            public void afterTextChanged(android.text.Editable value) { }
        });
        button.setOnClickListener(v -> {
            String address = email.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { showAuthError(status, t("email_invalid")); return; }
            if (authFormState.submitting() || !authFormState.beginCode(address, purpose)) return;
            requestPending[0] = true;
            refresh.run(); status.setText(t("auth_sending_code")); status.setTextColor(MUTED);
            JSONObject body = new JSONObject();
            try { body.put("email", address); body.put("purpose", purpose); } catch (Exception ignored) { }
            ApiClient.post(this, "auth.email.code", body, new ApiClient.Callback() {
                public void onSuccess(Object data) {
                    authFormState.finishCode(address, purpose, true);
                    requestPending[0] = false;
                    if (!authPageCurrent(page, button)) return;
                    refresh.run();
                    if (AuthFormState.address(address).equals(AuthFormState.address(email.getText().toString()))) {
                        String sentMessage = t("password_reset".equals(purpose) ? "reset_code_sent_check" : "code_sent_check");
                        showAuthSuccess(status, sentMessage);
                        toast(sentMessage);
                    }
                }
                public void onError(String message) {
                    authFormState.finishCode(address, purpose, false);
                    requestPending[0] = false;
                    if (!authPageCurrent(page, button)) return;
                    refresh.run();
                    if (AuthFormState.address(address).equals(AuthFormState.address(email.getText().toString()))) showAuthError(status, message);
                }
            });
        });
    }

    private ApiClient.Callback authCallback(TextView submit, TextView status) {
        final int page = authFormState.page();
        final boolean registration = authRegisterMode;
        return new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!authPageCurrent(page, submit)) return;
                authFormState.finishSubmit(); submit.setEnabled(true); submit.setText(t(registration ? "verify_register" : "login"));
                if (!(data instanceof JSONObject)) { showAuthError(status, t("server_format_invalid")); return; }
                JSONObject result = (JSONObject) data;
                String token = result.optString("token", "");
                if (token.isEmpty()) { showAuthError(status, t("login_token_missing")); return; }
                JSONObject user = result.optJSONObject("user");
                if(user==null||user.optInt("id")<=0){showAuthError(status,t("ui_profile_invalid"));return;}
                ApiClient.saveToken(MainActivity.this, token);
                acceptAuthenticatedUser(user);
                getWindow().setStatusBarColor(Color.WHITE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                authFormState.clearDrafts(); authFormState.nextPage(); authUiHandler.removeCallbacksAndMessages(null);
                toast(t(registration ? "register_success" : "login_success"));
                buildApp();
            }
            @Override public void onError(String message) {
                if (!authPageCurrent(page, submit)) return;
                authFormState.finishSubmit(); submit.setEnabled(true); submit.setText(t(registration ? "verify_register" : "login"));
                showAuthError(status, message);
            }
        };
    }

    private void startCodeCountdown(TextView button, int seconds) {
        Handler handler = new Handler(Looper.getMainLooper());
        int[] remaining = {seconds};
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            remaining[0]--;
            if (remaining[0] <= 0) {
                button.setEnabled(true);
                button.setText(t("resend"));
            } else {
                button.setText(remaining[0] + " " + t("seconds_retry"));
                handler.postDelayed(tick[0], 1000);
            }
        };
        button.setText(seconds + " " + t("seconds_retry"));
        handler.postDelayed(tick[0], 1000);
    }

    private void addDeviceInfo(JSONObject body) throws Exception {
        body.put("device_id", deviceId);
        body.put("device_model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        body.put("android_version", android.os.Build.VERSION.RELEASE);
        body.put("app_version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
    }

    private void syncDeviceInfo() {
        JSONObject body = new JSONObject();
        try {
            addDeviceInfo(body);
        } catch (Exception ignored) {
            return;
        }
        ApiClient.post(this, "profile.update", body, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) { }
            @Override public void onError(String message) { }
        });
    }

    private void startPresenceTracking() {
        if (currentUserId <= 0 || ApiClient.token(this).isEmpty()) return;
        presenceHandler.removeCallbacks(presenceHeartbeat);
        updatePresence(true);
        presenceHandler.postDelayed(presenceHeartbeat, PRESENCE_HEARTBEAT_INTERVAL_MS);
    }

    private void updatePresence(boolean online) {
        if (currentUserId <= 0 || ApiClient.token(this).isEmpty()) return;
        JSONObject request = new JSONObject();
        try { request.put("device_id", deviceId); request.put("online", online); }
        catch (Exception ignored) { return; }
        ApiClient.post(this, "presence.update", request, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) { }
            @Override public void onError(String message) { }
        });
    }

    private void addAuthStatus(LinearLayout page, TextView status) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        status.setMinHeight(dp(38));
        params.setMargins(0, dp(2), 0, dp(4));
        page.addView(status, params);
    }

    private void showAuthError(TextView status, String message) {
        status.setText(message);
        status.setTextColor(Color.rgb(207, 67, 67));
    }

    private void showAuthSuccess(TextView status,String message) {status.setText(message);status.setTextColor(Color.rgb(37,145,103));}

    private TextView authPrimaryButton(String label) {
        TextView button = centeredText(label, 16, Color.WHITE);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(round(BLUE, 14));
        return button;
    }

    private LinearLayout.LayoutParams authButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, dp(8), 0, dp(18));
        return params;
    }

    private void installSystemBackHandler() {
        if(Build.VERSION.SDK_INT>=33){
            systemBackCallback=this::onBackPressed;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBackCallback);
        }
    }

    @Override
    public void onBackPressed() {
        if (selectingMessages) { exitSelectionMode(); return; }
        if (isAppUpdatePageVisible()) { leaveAppUpdatePage(); return; }
        if (bottomNav == null && !canLeaveAuth()) return;
        if(bottomNav==null && authChildOpen){showAuthenticationPage();return;}
        // Child pages must unwind their own stack before the activity considers leaving.
        // Commerce owns a nested stack, followed by chat/detail pages, then the root tab.
        if (currentCommercePage && commerce != null && commerce.back()) {
            return;
        }
        if (chatWindowOpen) {
            if(activeToolPanel!=null&&activeToolPanel.getVisibility()==View.VISIBLE){activeToolPanel.setVisibility(View.GONE);return;}
            closeChatWindow();
            return;
        }
        if (subPageOpen) {
            closeSubPage();
            return;
        }
        if (askWindowOpen) {
            closeAskWindow();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(chatExperience!=null && chatExperience.activityResult(requestCode,resultCode,data))return;
        if (merchantForm != null && merchantForm.activityResult(requestCode,resultCode,data)) return;
        if(commerce!=null&&commerce.activityResult(requestCode,resultCode,data))return;
        if (requestCode == PICK_MALL_IMAGE) {
            final EditText target = mallUploadTarget;
            mallUploadTarget = null;
            Uri selected = ChatGallery.selectedUri(data);
            if (selected != null && target != null) {
                toast("正在上传图片…");
                MallUpload.upload(this, "merchant.media.upload", selected, new ApiClient.Callback() {
                    @Override public void onSuccess(Object value) {
                        if (!(value instanceof JSONObject)) return;
                        String url = ((JSONObject) value).optString("url");
                        String previous = target.getText().toString().trim();
                        target.setText(previous.isEmpty() ? url : previous + "\n" + url);
                        toast("图片已上传");
                    }
                    @Override public void onError(String message) { toast(message); }
                });
            } else if (resultCode == RESULT_OK) toast("未读取到相册图片，请重新选择");
            return;
        }
        if (requestCode == QR_SCAN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                String scanned = data.getStringExtra(QrScanActivity.EXTRA_SCAN_RESULT);
                if (scanned != null && !scanned.trim().isEmpty()) handleScannedCode(scanned.trim());
            }
            return;
        }
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanResult != null) {
            String scanned = scanResult.getContents();
            if (scanned != null) handleScannedCode(scanned);
            return;
        }
        if (requestCode == PICK_SCAN_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) decodeQrFromImage(data.getData());
            return;
        }
        if (requestCode == CAMERA_CHAT_PHOTO || requestCode == CAMERA_CHAT_VIDEO || requestCode == CAMERA_AVATAR) {
            Uri captured = pendingCaptureUri;
            pendingCaptureUri = null;
            if (resultCode == RESULT_OK && captured != null) {
                if (requestCode == CAMERA_AVATAR) uploadAvatar(captured);
                else uploadChatMedia(captured);
            } else {
                if (captured != null) getContentResolver().delete(captured, null, null);
                if (requestCode != CAMERA_AVATAR) sendingMessage = false;
            }
            return;
        }
        if ((requestCode == 1001 || requestCode == 1002) && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (requestCode == 1002) {
                chatBackgroundUri = data.getData();
                chatBackgroundPreset = 0;
                ownProfile().edit().putString("chat_background_uri", chatBackgroundUri.toString()).apply();
                ownProfile().edit().putInt("chat_background_preset", 0).apply();
                toast(t("background_updated"));
                return;
            }
            uploadAvatar(data.getData());
        }
        if(requestCode==2001||requestCode==2002||requestCode==2003){
            sendingMessage=false;
            if(resultCode==RESULT_OK){
                java.util.List<Uri> selected=ChatGallery.selectedUris(data);
                if(selected.isEmpty())toast("未读取到附件，请重新选择");
                else if(pendingMediaOwnerId!=currentUserId)toast("账号已切换，请重新选择附件");
                else if(requestCode==2003){
                    for(Uri uri:selected){
                        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
                        outbox().stageMedia(pendingMediaChatId,pendingMediaGroupId,uri,"file",attachmentName(uri));
                    }
                    toast("文件已加入发送队列");
                }else previewChatAttachments(selected,pendingMediaChatId,pendingMediaGroupId);
            }
            return;
        }
        if (requestCode == 1003 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri selectedAvatar = data.getData();
            ApiClient.upload(this, "upload.message", selectedAvatar, new ApiClient.Callback() {
                @Override public void onSuccess(Object result) {
                    if (!(result instanceof JSONObject)) { toast(t("group_avatar_upload_failed")); return; }
                    String url = ((JSONObject) result).optString("url", "");
                    JSONObject request = new JSONObject();
                    try { request.put("group_id", activeDetailGroupId); request.put("avatar_url", url); } catch (Exception ignored) { return; }
                    ApiClient.post(MainActivity.this, "groups.update", request, new ApiClient.Callback() {
                        @Override public void onSuccess(Object value) {
                            preferences.edit().putString("group_avatar_" + activeGroupName, url).apply();
                            JSONArray snapshot = readChatSnapshot();
                            for (int i = 0; i < snapshot.length(); i++) {
                                JSONObject group = snapshot.optJSONObject(i);
                                if (group == null || !"group".equals(group.optString("kind")) || group.optInt("id") != activeDetailGroupId) continue;
                                try { group.put("avatar_url", url); } catch (Exception ignored) { }
                            }
                            storeChatSnapshot(snapshot);
                            toast(t("group_avatar_updated"));
                            openGroupDetailPage(activeGroupName, t("ui_group_avatar"), "#5B7CFA", "#9B5DE5");
                        }
                        @Override public void onError(String message) { toast(message); }
                    });
                }
                @Override public void onError(String message) { toast(message); }
            });
        }
    }

    private void handleScannedCode(String value) {
        String scanned = value == null ? "" : value.trim();
        if (scanned.startsWith("SPXGROUP:")) {
            joinGroupByCode(scanned.substring("SPXGROUP:".length()).trim());
        } else if (scanned.matches("(?i)[a-z0-9]{6}")) {
            joinGroupByCode(scanned);
        } else {
            String spxId = scanned.startsWith("SPXUSER:") ? scanned.substring("SPXUSER:".length()).trim() : scanned;
            if (spxId.startsWith("http://") || spxId.startsWith("https://")) {
                try {
                    String invite = Uri.parse(spxId).getQueryParameter("invite");
                    if (invite != null) spxId = invite;
                } catch (Exception ignored) { }
            }
            if (spxId.isEmpty()) toast(t("qr_unrecognized"));
            else openScannedUser(spxId);
        }
    }

    private void openScannedUser(String spxId) {
        final String lookupId = spxId;
        Map<String, String> params = new HashMap<>();
        params.put("q", lookupId);
        ApiClient.get(this, "contacts.search", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) { toast(t("qr_user_not_found")); return; }
                JSONArray matches = (JSONArray) data;
                JSONObject match = null;
                for (int i = 0; i < matches.length(); i++) {
                    JSONObject candidate = matches.optJSONObject(i);
                    if (candidate != null && (candidate.optString("spx_id").equalsIgnoreCase(lookupId)
                            || String.valueOf(candidate.optInt("id")).equals(lookupId))) { match = candidate; break; }
                }
                if (match == null) { toast(t("qr_user_not_found")); return; }
                int userId = match.optInt("id");
                loadUserProfile(userId, new UserProfileCallback() {
                    @Override public void onProfile(JSONObject profile) { openFriendProfilePage(profile); }
                    @Override public void onError(String message) { toast(t("ui_profile_load_failed", message)); }
                });
            }
            @Override public void onError(String message) { toast(t("ui_profile_load_failed", message)); }
        });
    }

    private void decodeQrFromImage(Uri uri) {
        new Thread(() -> {
            String decoded = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input, null, bounds); }
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                Bitmap bitmap;
                try (InputStream input = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
                if (bitmap == null) throw new IllegalArgumentException("selected image could not be read");
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                MultiFormatReader reader = new MultiFormatReader();
                Map<DecodeHintType, Object> hints = new HashMap<>();
                hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
                decoded = reader.decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))), hints).getText();
                bitmap.recycle();
            } catch (Exception ignored) { }
            String result = decoded;
            runOnUiThread(() -> {
                if (result == null || result.trim().isEmpty()) toast(t("qr_image_empty"));
                else handleScannedCode(result);
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode==NOTIFICATION_PERMISSION_REQUEST){if(currentUserId>0)ChatBadge.update(this,currentUserId,readChatSnapshot());return;}
        if(ChatMediaViewer.permissionResult(this,requestCode,grantResults))return;
        if(chatCall!=null&&chatCall.permissionResult(requestCode,grantResults))return;
        if (requestCode == QR_SCAN_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
                startActivityForResult(new Intent(this, QrScanActivity.class), QR_SCAN_REQUEST);
            else toast("需要相机权限才能扫码");
            return;
        }
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST) {
            if (grantResults.length == 0 || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)
                toast(t("microphone_required"));
            return;
        }
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == android.content.pm.PackageManager.PERMISSION_GRANTED;
        int cameraRequest = pendingCameraRequest;
        pendingCameraRequest = 0;
        if (granted) launchCamera(cameraRequest);
        else {
            if (cameraRequest != CAMERA_AVATAR) sendingMessage = false;
            toast(t("camera_required"));
        }
    }

    private void uploadAvatar(Uri uri) {
        ApiClient.upload(this, "upload.avatar", uri, new ApiClient.Callback() {
            @Override public void onSuccess(Object result) {
                if (result instanceof JSONObject) {
                    String url = ((JSONObject) result).optString("url", "");
                    if (!url.isEmpty()) saveRemoteAvatar(url, true);
                    else { toast(t("avatar_url_missing")); return; }
                }
                toast(t("avatar_saved"));
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void uploadChatMedia(Uri uri){
        sendingMessage=false;if(pendingMediaOwnerId!=currentUserId){toast("账号已切换，请重新选择附件");return;}
        String mime=getContentResolver().getType(uri);outbox().stageMedia(pendingMediaChatId,pendingMediaGroupId,uri,mime!=null&&mime.startsWith("video/")?"video":"image",mime!=null&&mime.startsWith("video/")?"[视频]":"[图片]");
    }
    private void previewChatAttachments(java.util.List<Uri> selected,int chat,int group){
        if(selected.size()>9){toast("一次最多选择 9 个附件，请重新选择");return;}final int owner=currentUserId;LinearLayout box=column();box.setPadding(dp(16),dp(12),dp(16),dp(12));
        HorizontalScrollView gallery=new HorizontalScrollView(this);LinearLayout tiles=row();gallery.addView(tiles);box.addView(gallery,new LinearLayout.LayoutParams(-1,dp(108)));
        for(Uri uri:selected){FrameLayout tile=new FrameLayout(this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(92),dp(92));lp.setMargins(0,0,dp(8),0);tiles.addView(tile,lp);ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(round(Color.rgb(238,242,248),10));image.setContentDescription("待发送附件预览");tile.addView(image,new FrameLayout.LayoutParams(-1,-1));String mime=getContentResolver().getType(uri);if(mime!=null&&mime.startsWith("video/")){TextView video=centeredText("▶  视频",14,TEXT);video.setBackground(round(Color.rgb(238,242,248),10));tile.addView(video,new FrameLayout.LayoutParams(-1,-1));}else if(mime!=null&&mime.startsWith("image/"))ChatMediaWork.execute(()->{Bitmap bitmap=null;try{BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opts);}opts.inSampleSize=1;while(opts.outWidth/opts.inSampleSize>dp(184)||opts.outHeight/opts.inSampleSize>dp(184))opts.inSampleSize*=2;opts.inJustDecodeBounds=false;try(InputStream in=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,opts);}}catch(Exception ignored){}final Bitmap result=bitmap;image.post(()->{if(image.isAttachedToWindow()&&result!=null)image.setImageBitmap(result);});});else{TextView file=centeredText("▤\n文件",14,TEXT);file.setBackground(round(Color.rgb(238,242,248),10));tile.addView(file,new FrameLayout.LayoutParams(-1,-1));}}
        box.addView(bodyText("发送后可分别查看进度，失败的附件可单独重试。",12,MUTED));new AlertDialog.Builder(this).setTitle("发送 "+selected.size()+" 个附件").setView(box).setNegativeButton(t("cancel"),null).setPositiveButton("发送",(d,w)->{if(owner!=currentUserId){toast("账号已变化，请重新选择");return;}for(Uri uri:selected){String mime=getContentResolver().getType(uri);boolean video=mime!=null&&mime.startsWith("video/");boolean image=mime!=null&&mime.startsWith("image/");String kind=video?"video":image?"image":"file";String display=attachmentName(uri);outbox().stageMedia(chat,group,uri,kind,video?"[视频]":image?"[图片]":display);}toast("附件已加入发送队列");}).show();
    }
    private String attachmentName(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}String p=uri.getLastPathSegment();return p==null||p.isEmpty()?"文件":p;}
    private View showPendingMediaBubble(Uri uri) {
        if (activeMessageList == null || activeMessageScroll == null) return null;
        String mime = getContentResolver().getType(uri);
        boolean video = mime != null && mime.startsWith("video/");
        FrameLayout bubble = new FrameLayout(this);
        bubble.setBackground(round(Color.WHITE, 12));
        TextView label = centeredText(video ? t("ui_media_uploading_video") : t("ui_media_uploading_image"), 13, MUTED);
        bubble.addView(label, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar spinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        spinnerParams.bottomMargin = dp(28);
        bubble.addView(spinner, spinnerParams);
        LinearLayout line = row();
        line.setGravity(Gravity.RIGHT);
        line.addView(bubble, new LinearLayout.LayoutParams(dp(210), dp(100)));
        activeMessageList.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return label;
    }

    private void removePendingMediaBubble() {
        if (pendingMediaBubble == null) return;
        View label = pendingMediaBubble;
        View bubble = label.getParent() instanceof View ? (View) label.getParent() : null;
        View line = bubble != null && bubble.getParent() instanceof View ? (View) bubble.getParent() : null;
        if (line != null && line.getParent() instanceof ViewGroup) ((ViewGroup) line.getParent()).removeView(line);
        pendingMediaBubble = null;
    }

    private void requestCamera(int requestCode) {
        pendingCameraRequest = requestCode;
        java.util.ArrayList<String> required = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            required.add(android.Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            required.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (required.isEmpty()) launchCamera(requestCode);
        else requestPermissions(required.toArray(new String[0]), CAMERA_PERMISSION_REQUEST);
    }

    private void launchCamera(int requestCode) {
        boolean video = requestCode == CAMERA_CHAT_VIDEO;
        boolean avatar = requestCode == CAMERA_AVATAR;
        String mime = video ? "video/mp4" : "image/jpeg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "spx_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg"));
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.RELATIVE_PATH, video ? "Movies/SPX IM" : "Pictures/SPX IM");
        Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try {
            pendingCaptureUri = getContentResolver().insert(collection, values);
            if (pendingCaptureUri == null) throw new IllegalStateException("media file could not be created");
            Intent intent = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCaptureUri);
            intent.setClipData(ClipData.newRawUri("SPX media capture", pendingCaptureUri));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (video) intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            startActivityForResult(intent, requestCode);
        } catch (Exception exception) {
            if (pendingCaptureUri != null) getContentResolver().delete(pendingCaptureUri, null, null);
            pendingCaptureUri = null;
            if (!avatar) sendingMessage = false;
            toast(t("camera_start_failed"));
        }
    }

    private boolean startVoiceRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_PERMISSION_REQUEST);
            toast(t("record_permission_retry"));
            return false;
        }
        try {
            voiceRecordingFile = new File(getCacheDir(), "voice_" + UUID.randomUUID() + ".m4a");
            if(chatSoundEffects!=null)chatSoundEffects.stop();
            voiceRecorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            voiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setMaxDuration(60000);
            voiceRecorder.setOutputFile(voiceRecordingFile.getAbsolutePath());
            voiceRecorder.setOnInfoListener((recorder, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishVoiceRecording(true);
            });
            voiceRecorder.prepare();
            voiceRecorder.start();
            voiceRecordingStartedAt = System.currentTimeMillis();
            voiceRecordingCancelled = false;
            return true;
        } catch (Exception exception) {
            releaseVoiceRecorder();
            if (voiceRecordingFile != null) voiceRecordingFile.delete();
            toast(t("record_start_failed"));
            return false;
        }
    }

    private void finishVoiceRecording(boolean send) {
        if (voiceRecorder == null) return;
        File recording = voiceRecordingFile;
        long durationMs = System.currentTimeMillis() - voiceRecordingStartedAt;
        try { voiceRecorder.stop(); } catch (Exception ignored) { send = false; }
        releaseVoiceRecorder();
        voiceRecordingFile = null;
        if (!send || voiceRecordingCancelled || recording == null || !recording.isFile() || durationMs < 700) {
            if (recording != null) recording.delete();
            if (send && durationMs < 700) toast(t("record_too_short"));
            return;
        }
        int seconds=Math.max(1,(int)Math.ceil(durationMs/1000.0));
        outbox().stageMedia(activeChatId,activeGroupId,Uri.fromFile(recording),"voice",t("ui_voice_duration",seconds));
        sendingMessage=false;
    }

    private void releaseVoiceRecorder() {
        if (voiceRecorder == null) return;
        try { voiceRecorder.reset(); } catch (Exception ignored) { }
        try { voiceRecorder.release(); } catch (Exception ignored) { }
        voiceRecorder = null;
    }

    private SharedPreferences ownProfile() {
        return getSharedPreferences("spx_profile_"+AccountScope.id(ApiClient.baseUrl(this),currentUserId),MODE_PRIVATE);
    }

    private String accountCacheKey(String name) {
        return AccountScope.id(ApiClient.baseUrl(this),currentUserId)+"_"+name;
    }

    private void migrateAccountLists(int userId) {
        if(userId<=0 || ownProfile().getBoolean("lists_migrated_v1",false))return;
        // Legacy lists already included the user ID. Only migrate the currently logged-in owner.
        if(preferences.getInt("current_user_id",0)==userId && preferences.getString("current_account_server",ApiClient.baseUrl(this)).equals(ApiClient.baseUrl(this))){
            SharedPreferences.Editor edit=preferences.edit();
            for(String name:new String[]{"chat_snapshot","chat_snapshot_sync","contacts_cache","groups_cache","user_profile","remark_user","group_detail","wallet_bsc_binding"}){
                String legacy=name+"_"+userId;
                for(Map.Entry<String,?> entry:preferences.getAll().entrySet())if(entry.getKey().equals(legacy)||entry.getKey().startsWith(legacy+"_")){
                    String scoped=accountCacheKey(name)+entry.getKey().substring(legacy.length());if(preferences.contains(scoped))continue;
                    Object value=entry.getValue();if(value instanceof String)edit.putString(scoped,(String)value);else if(value instanceof Long)edit.putLong(scoped,(Long)value);
                }
            }
            edit.apply();
        }
        ownProfile().edit().putBoolean("lists_migrated_v1",true).apply();
    }

    private void loadOwnProfile(int userId) {
        currentUserId=userId;
        SharedPreferences own=ownProfile();
        migrateAccountLists(userId);
        userName=normalizeOptionalText(own.getString("user_name",t("default_user_name")));
        if(userName.isEmpty())userName=t("default_user_name");
        userEmail=own.getString("user_email","");currentUserSpxId=own.getString("spx_id","");
        remoteAvatarUrl=normalizeOptionalText(own.getString("avatar_url",""));
        String avatar=own.getString("avatar_uri","");userAvatarUri=avatar.isEmpty()?null:Uri.parse(avatar);
        addressName=own.getString("address_name","");addressPhone=own.getString("address_phone","");addressDetail=own.getString("address_detail","");
        addressProvince=own.getString("address_province","福建省");addressCity=own.getString("address_city","泉州市");addressDistrict=own.getString("address_district","石狮市");
        String background=own.getString("chat_background_uri","");chatBackgroundUri=background.isEmpty()?null:Uri.parse(background);
        chatBackgroundPreset=own.getInt("chat_background_preset",0);
    }

    private boolean acceptAuthenticatedUser(JSONObject user) {
        if(user==null||user.optInt("id")<=0)return false;
        if(currentUserId!=user.optInt("id"))loadOwnProfile(user.optInt("id"));
        String name=normalizeOptionalText(user.optString("nickname",user.optString("username","")));
        userName=name.isEmpty()?t("default_user_name"):name;
        userEmail=normalizeOptionalText(user.optString("email",""));currentUserSpxId=normalizeOptionalText(user.optString("spx_id",""));
        remoteAvatarUrl=normalizeOptionalText(user.optString("avatar_url",""));userAvatarUri=null;
        ownProfile().edit().putString("user_name",userName).putString("user_email",userEmail).putString("spx_id",currentUserSpxId)
                .putString("avatar_url",remoteAvatarUrl).remove("avatar_uri").apply();
        preferences.edit().putInt("current_user_id",currentUserId).putString("current_account_server",ApiClient.baseUrl(this)).apply();
        return true;
    }

    private void resetAccountSurface() {
        for(Dialog dialog:new ArrayList<>(accountDialogs))dialog.dismiss();accountDialogs.clear();
        pageHistory.clear();chatHomePage=null;activeChatRows=null;activeChatRowsAdapter=null;activeChatListView=null;activeChatSearchField=null;
        chatHomeUserId=0;chatHomeSyncing=false;chatSnapshotRevision++;renderedChatSnapshotSignature="";
        activeMessageList=null;activeMessageScroll=null;activeMessageInput=null;activeChatTitle=null;
        if(commerce!=null){commerce.dispose();commerce=null;}
        contactsRefresh=null;contactsRender=null;contactsPage=null;contactsRefreshing=false;contactsRevision++;activeChatStatus=null;activeTab=0;
        priceSession=null;priceWaiters=null;cachedUsdPrice=0;cachedUsdPriceAt=0;
        loadOwnProfile(0);
        preferences.edit().putInt("current_user_id",0).apply();
    }

    private void buildApp() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        applySystemInsets(root);

        content = new FrameLayout(this);
        content.setBackgroundColor(BG);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        offlineBanner = centeredText("当前无网络连接，消息将在恢复后自动同步", 12, Color.rgb(196, 106, 20));
        offlineBanner.setGravity(Gravity.CENTER);
        offlineBanner.setPadding(dp(14), dp(8), dp(14), dp(8));
        offlineBanner.setBackgroundColor(Color.rgb(255, 244, 230));
        offlineBanner.setVisibility(View.GONE);
        root.addView(offlineBanner, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        startConnectivityMonitor();

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER_VERTICAL);
        bottomNav.setPadding(dp(8), dp(6), dp(8), dp(10));
        bottomNav.setBackground(stroke(Color.WHITE, MallUi.LINE, 24, 1));
        bottomNav.setElevation(dp(5));
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottomNav.setMinimumHeight(dp(76));
        navParams.setMargins(dp(18), 0, dp(18), dp(12));
        root.addView(bottomNav, navParams);

        updateDownloadChip = centeredText("", 12, Color.WHITE);
        updateDownloadChip.setPadding(dp(15), dp(12), dp(15), dp(12));
        updateDownloadChip.setBackground(round(BLUE, 22));
        updateDownloadChip.setElevation(dp(6));
        updateDownloadChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams updateChipParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        updateChipParams.setMargins(dp(16), 0, dp(16), dp(104));
        root.addView(updateDownloadChip, updateChipParams);
        updateDownloadChip.setOnClickListener(v -> { if (updateDownload.release != null) openAppUpdatePage(updateDownload.release); });
        content.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) { parent.post(MainActivity.this::renderAppUpdateDownload); }
            @Override public void onChildViewRemoved(View parent, View child) { parent.post(MainActivity.this::renderAppUpdateDownload); }
        });

        String[] labels = {t("chats"), t("contacts"), t("explore"), t("me")};
        String[] icons = {"◉", "♙", "◇", "●"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            TextView item = navItem(icons[i], labels[i]);
            item.setText(labels[i]);
            AppNavIcon navigationIcon = new AppNavIcon(i, dp(23), MUTED);
            item.setCompoundDrawablesWithIntrinsicBounds(null, navigationIcon, null, null);
            item.setCompoundDrawablePadding(dp(4));
            item.setContentDescription(labels[i]);
            item.setOnClickListener(v -> switchTab(tab));
            bottomNav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        bottomNav.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top + dp(24);
            if (bottomNav.getVisibility() == View.VISIBLE && content.getPaddingBottom() != height)
                content.setPadding(0, 0, 0, height);
        });
        setContentView(root);
        switchTab(0);
        startRealtimeSocket();
        calls();
        if(currentUserId>0){outbox();chatOutbox.pump();}
        requestChatNotificationPermission();
        startPresenceTracking();
        checkAppUpdate(false);
        openPendingSharedProduct();openPendingChatNotification();
    }

    @Override protected void onResume() {
        super.onResume();
        hidePrivacyShield();
        try { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); } catch (Exception ignored) { }
        if(commerce!=null)commerce.foreground();
        AppNotice.resume(this);
        if(currentUserId>0&&!ApiClient.token(this).isEmpty())ChatBadge.update(this,currentUserId,readChatSnapshot());
        boolean returnedToForeground = !appInForeground;
        appInForeground = true;
        updateCheckHandler.removeCallbacks(periodicUpdateCheck);
        updateCheckHandler.postDelayed(periodicUpdateCheck, UPDATE_CHECK_INTERVAL_MS);
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                if (pendingUpdateApk != null && pendingUpdateApk.isFile()) installDownloadedApk(pendingUpdateApk);
            } else {
                toast(t("install_permission_missing"));
            }
        }
        if(currentUserId>0&&bottomNav!=null){startPresenceTracking();outbox().pump();ChatCall call=calls();if(call!=null)call.foreground(true);}
        if (returnedToForeground) checkAppUpdate(false);
        if(returnedToForeground&&activeTab==1&&!subPageOpen&&contactsRefresh!=null)contactsRefresh.run();
        if (returnedToForeground && chatWindowOpen && activeMessageList != null && activeMessageScroll != null)
            loadChatHistory(activeMessageList, activeMessageScroll, activeChatName, activeChatAvatar, activeChatC1, activeChatC2, chatSocketGeneration);
    }

    @Override protected void onPause() {
        showPrivacyShield();
        // 多任务缩略图由系统缓存，View 遮罩来不及绘制；这里同时禁止后台截图。
        try { getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); } catch (Exception ignored) { }
        hideVoiceOverlay();
        if(chatMediaViewer!=null)chatMediaViewer.pause();
        saveActiveChatDraft();
        if(commerce!=null)commerce.pause();
        appInForeground = false;
        if(chatCall!=null)chatCall.foreground(false);
        updateCheckHandler.removeCallbacks(periodicUpdateCheck);
        presenceHandler.removeCallbacks(presenceHeartbeat);
        if (currentUserId > 0) updatePresence(false);
        super.onPause();
    }

    /** 切到后台时盖一层，避免聊天内容出现在多任务缩略图里。 */
    private void showPrivacyShield() {
        if (privacyShield != null) return;
        ViewGroup host = findViewById(android.R.id.content);
        if (host == null) return;
        LinearLayout shield = column();
        shield.setGravity(Gravity.CENTER);
        shield.setBackgroundColor(BG);
        shield.setClickable(true);
        shield.addView(centeredText("SPX CHAT", 18, MUTED));
        host.addView(shield, new FrameLayout.LayoutParams(-1, -1));
        privacyShield = shield;
    }
    private void hidePrivacyShield() {
        if (privacyShield == null) return;
        ViewGroup parent = (ViewGroup) privacyShield.getParent();
        if (parent != null) parent.removeView(privacyShield);
        privacyShield = null;
    }

    private void startConnectivityMonitor() {
        if (connectivityCallback != null) return;
        try {
            connectivityManager = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            connectivityCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) { runOnUiThread(() -> setOfflineBanner(false)); }
                @Override public void onLost(android.net.Network network) { runOnUiThread(() -> setOfflineBanner(true)); }
            };
            android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connectivityManager.registerNetworkCallback(request, connectivityCallback);
            setOfflineBanner(connectivityManager.getActiveNetwork() == null);
        } catch (Exception ignored) { connectivityCallback = null; }
    }
    private void setOfflineBanner(boolean offline) {
        if (offlineBanner == null) return;
        offlineBanner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    @Override protected void onStop() {
        AppNotice.stop(this);
        if(chatSoundEffects!=null)chatSoundEffects.stop();
        if(chatCall!=null&&!chatCall.hasPendingPermission())chatCall.stopped();
        stopActiveVoicePlayback();
        super.onStop();
    }

    private void createChatNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHAT_NOTIFICATION_CHANNEL, t("new_message"), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(t("ui_chat_notification_desc")); channel.setShowBadge(true);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {manager.createNotificationChannel(channel);NotificationChannel packets=new NotificationChannel(RED_PACKET_NOTIFICATION_CHANNEL,"红包消息",NotificationManager.IMPORTANCE_HIGH);packets.setShowBadge(true);packets.setSound(Uri.parse("android.resource://"+getPackageName()+"/"+R.raw.red_packet),new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());manager.createNotificationChannel(packets);}
    }

    private void requestChatNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private boolean showChatNotification(JSONObject message, int chatId, int groupId) {
        if (!ChatBadge.permitted(this)||!preferences.getBoolean("notifications_enabled",true)) return false;
        String sender = message.optString("nickname", t("new_message"));
        String content = message.optString("content", t("ui_received_message"));
        if ("image".equals(message.optString("message_type"))) content = t("ui_image_placeholder");
        else if ("video".equals(message.optString("message_type"))) content = t("ui_video_placeholder");
        else if ("voice".equals(message.optString("message_type"))||"audio".equals(message.optString("message_type"))) content="[语音]";
        else if ("file".equals(message.optString("message_type"))) content="[文件]";
        else if ("location".equals(message.optString("message_type"))) content="[位置]";
        else if ("red_packet".equals(message.optString("message_type"))) content="[SPX001 红包]";
        else if ("product".equals(message.optString("message_type"))) content=productPreview(content);
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String notificationTag="chat:"+currentUserId+":"+(groupId>0?"g:"+groupId:"c:"+chatId);
        intent.setData(Uri.parse("spxchat://notification/"+currentUserId+"/"+(groupId>0?"group/"+groupId:"chat/"+chatId)));
        intent.putExtra("notification_chat_id",chatId).putExtra("notification_group_id",groupId).putExtra("notification_user_id",currentUserId).putExtra("notification_chat_name",sender);
        PendingIntent pending = PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String baseChannel="red_packet".equals(message.optString("message_type"))?RED_PACKET_NOTIFICATION_CHANNEL:CHAT_NOTIFICATION_CHANNEL;
        String deliveryChannel=baseChannel+(ChatBadge.badgeEnabled(this)?"":"_no_badge");
        if(Build.VERSION.SDK_INT>=26&&!ChatBadge.badgeEnabled(this)){NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);NotificationChannel quietBadge=new NotificationChannel(deliveryChannel,"red_packet".equals(message.optString("message_type"))?"红包消息（无角标）":"聊天消息（无角标）",NotificationManager.IMPORTANCE_HIGH);quietBadge.setShowBadge(false);if("red_packet".equals(message.optString("message_type")))quietBadge.setSound(Uri.parse("android.resource://"+getPackageName()+"/"+R.raw.red_packet),new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());if(nm!=null)nm.createNotificationChannel(quietBadge);}
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, deliveryChannel)
                : new Notification.Builder(this);
        boolean mentioned = isCurrentUserMentioned(message);
        if(Build.VERSION.SDK_INT<26&&"red_packet".equals(message.optString("message_type")))builder.setSound(Uri.parse("android.resource://"+getPackageName()+"/"+R.raw.red_packet));
        if(!preferences.getBoolean("notifications_preview",true))content="你收到了一条新消息";
        String title=groupId>0?cachedGroupName(groupId):sender;if(title==null||title.isEmpty())title=groupId>0?t("chat_group"):t("new_message");
        if(preferences.getBoolean("notifications_preview",true)&&groupId>0)content=sender+"："+content;
        if(mentioned)content="[有人@你] "+content;
        builder.setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(mentioned ? Notification.PRIORITY_MAX : Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setGroup(ChatBadge.GROUP).setNumber(ChatBadge.conversationCount(readChatSnapshot(),chatId,groupId))
                .setAutoCancel(true)
                .setContentIntent(pending);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=manager.getNotificationChannel(deliveryChannel);if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;}
        if (Build.VERSION.SDK_INT >= 26) builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).setBadgeIconType(Notification.BADGE_ICON_SMALL);
        try { manager.notify(notificationTag,0,builder.build()); return true; } catch (SecurityException ignored) { return false; }
    }

    private void showIncomingMessageBanner(JSONObject message, int chatId, int groupId) {
        if (content == null || isFinishing()) return;
        incomingBannerHandler.removeCallbacks(hideIncomingBanner);
        if (incomingMessageBanner != null && incomingMessageBanner.getParent() instanceof ViewGroup) {
            ((ViewGroup) incomingMessageBanner.getParent()).removeView(incomingMessageBanner);
        }
        boolean group = groupId > 0;
        String sender = normalizeOptionalText(message.optString(group ? "group_name" : "nickname", ""));
        if (group && sender.isEmpty()) sender = cachedGroupName(groupId);
        if (sender.isEmpty()) sender = group ? t("chat_group") : t("new_message");
        String preview = normalizeOptionalText(message.optString("content", t("ui_received_message")));
        String type = message.optString("message_type", "text");
        if ("image".equals(type)) preview = t("ui_image_placeholder");
        else if ("video".equals(type)) preview = t("ui_video_placeholder");
        else if ("voice".equals(type)) preview = t("ui_voice_placeholder");
        else if ("product".equals(type)) preview=productPreview(preview);

        LinearLayout banner = row();
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(10), dp(16), dp(10));
        banner.setBackground(stroke(Color.WHITE, BLUE, 14, 1));
        final String bannerSender = sender;
        String avatarText = bannerSender.isEmpty() ? t("ui_new_avatar") : bannerSender.substring(0, 1);
        banner.addView(avatarWithRemoteImage(avatarText, message.optString("avatar_url", ""), "#A8C0FF", "#3F5EFB", 42));
        LinearLayout text = column();
        text.setPadding(dp(12), 0, dp(8), 0);
        TextView title = bodyText(sender, 15, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        TextView body = bodyText(preview, 13, MUTED);
        body.setSingleLine(true);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(body);
        banner.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        banner.setOnClickListener(v -> {
            incomingBannerHandler.removeCallbacks(hideIncomingBanner);
            hideIncomingBanner.run();
            openChatWindow(chatId, groupId, bannerSender, avatarText, group ? "group" : "online", "#A8C0FF", "#3F5EFB");
        });
        incomingMessageBanner = banner;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        params.setMargins(dp(12), dp(10), dp(12), 0);
        content.addView(banner, params);
        incomingBannerHandler.postDelayed(hideIncomingBanner, 4500L);
    }

    private TextView navItem(String icon, String label) {
        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setText(icon + "\n" + label);
        view.setTextSize(12);
        view.setTextColor(MUTED);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        view.setPadding(0, dp(4), 0, dp(4));
        view.setMinHeight(dp(56));
        view.setMinimumHeight(dp(56));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private void setBottomNavVisible(boolean visible) {
        bottomNav.setVisibility(visible ? View.VISIBLE : View.GONE);
        content.setPadding(0, 0, 0, visible ? bottomNav.getHeight() + dp(24) : 0);
    }

    private void switchTab(int tab) {
        if (tab == activeTab && !subPageOpen && !chatWindowOpen && !askWindowOpen
                && content != null && content.getChildCount() > 0) return;
        pageHistory.clear();
        currentCommercePage=false;
        pendingProductLoading=null;
        if(commerce!=null)commerce.reset();
        activeTab = tab;
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            TextView item = (TextView) bottomNav.getChildAt(i);
            item.setTextColor(i == tab ? BLUE : MUTED);
            item.setBackground(round(i == tab ? MallUi.TINT : Color.TRANSPARENT, 18));
            item.setSelected(i == tab);
            if (item.getCompoundDrawables()[1] instanceof AppNavIcon)
                ((AppNavIcon)item.getCompoundDrawables()[1]).color(i == tab ? BLUE : MUTED);
        }
        renderTab(tab);
    }

    private void renderTab(int tab) {
        if (tab == 0 && chatHomePage != null && chatHomeUserId == currentUserId
                && chatHomeLanguage.equals(appLanguage) && chatHomePage.getParent() == content) {
            refreshVisibleChatList();
            syncChatHome();
            return;
        }
        content.removeAllViews();
        if (tab == 0) {
            if (chatHomePage != null && chatHomeUserId == currentUserId && chatHomeLanguage.equals(appLanguage)) {
                if (chatHomePage.getParent() instanceof ViewGroup) ((ViewGroup) chatHomePage.getParent()).removeView(chatHomePage);
                content.addView(chatHomePage, new FrameLayout.LayoutParams(-1, -1));
                refreshVisibleChatList();
                syncChatHome();
            } else buildChatPage();
        }
        else if (tab == 1) buildContactsPage();
        else if (tab == 2) buildEcoPage();
        else buildProfilePage();
    }

    private View userAvatarView(int size) {
        TextView fallback = avatar(userName.substring(0, 1), "#3F5EFB", "#9B5DE5", size);
        if (!remoteAvatarUrl.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(Color.WHITE, 50));
            image.setClipToOutline(true);
            image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
            loadAvatarInto(image, remoteAvatarUrl);
            return image;
        }
        if (userAvatarUri != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.WHITE);
            border.setCornerRadius(dp(50));
            border.setStroke(dp(3), Color.WHITE);
            image.setBackground(border);
            image.setClipToOutline(true);
            image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
            image.post(() -> {
                java.io.InputStream input = null;
                try {
                    input = getContentResolver().openInputStream(userAvatarUri);
                    android.graphics.drawable.Drawable drawable = android.graphics.drawable.Drawable.createFromStream(input, userAvatarUri.toString());
                    image.setImageDrawable(drawable);
                } catch (Exception ignored) {
                } finally {
                    if (input != null) {
                        try {
                            input.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            return image;
        }
        return fallback;
    }

    private void saveRemoteAvatar(String url, boolean refresh) {
        remoteAvatarUrl = url;
        ownProfile().edit().putString("avatar_url", url).apply();
        invalidateAvatarCache(url);
        if (refresh && bottomNav != null) runOnUiThread(() -> renderTab(3));
    }

    private File avatarCacheFile(String url) {
        return new File(new File(getFilesDir(), "avatar-cache"), Integer.toHexString(url.hashCode()));
    }

    private void invalidateAvatarCache(String url) {
        if (url == null || url.isEmpty()) return;
        avatarCacheFile(url).delete();
    }

    private String resolveAssetUrl(String value) {
        String url = normalizeOptionalText(value);
        if (url.isEmpty()) return url;
        if (url.matches("(?i)^https?://.*$")) {
            try {
                URL parsed = new URL(url);
                String path = parsed.getPath();
                if (path.startsWith("/uploads/") || path.startsWith("/avatars/")) {
                    return parsed.getProtocol() + "://" + parsed.getAuthority() + "/spxchat" + path;
                }
            } catch (Exception ignored) { }
            return url;
        }
        String base = ApiClient.connectionBaseUrl(this).replaceAll("/+$", "");
        if (url.startsWith("/spxchat/")) return base.endsWith("/spxchat") ? base + url.substring("/spxchat".length()) : base + url;
        if (url.startsWith("/")) return (base.endsWith("/spxchat") ? base : base + "/spxchat") + url;
        return (base.endsWith("/spxchat") ? base : base + "/spxchat") + "/" + url;
    }

    private void loadAvatarInto(ImageView image, String url) {
        final String assetUrl = resolveAssetUrl(url);
        if (assetUrl.isEmpty()) return;
        image.setTag(assetUrl);
        image.setVisibility(View.VISIBLE);
        final int decodeSize = dp(96);
        ChatMediaWork.whenVisible(image, () -> ChatMediaWork.executeAvatar(assetUrl, () -> {
            if (!image.isAttachedToWindow() || !assetUrl.equals(image.getTag())) return;
            File cache = avatarCacheFile(assetUrl);
            boolean cacheFresh = cache.isFile() && System.currentTimeMillis() - cache.lastModified() < 5 * 60 * 1000L;
            if (cache.isFile()) {
                Bitmap cached = decodeBitmap(cache, decodeSize, decodeSize);
                if (cached != null) {
                    runOnUiThread(() -> { if (image.isAttachedToWindow() && assetUrl.equals(image.getTag())) image.setImageBitmap(cached); });
                    if (cacheFresh) return;
                }
            }
            HttpURLConnection connection = null;
            File temporary = new File(cache.getParentFile(), cache.getName() + "." + UUID.randomUUID() + ".part");
            try {
                connection = ApiClient.openConnection(this, assetUrl);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                File directory = cache.getParentFile();
                if (directory != null && !directory.exists()) directory.mkdirs();
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                if (!temporary.renameTo(cache)) {
                    cache.delete();
                    if (!temporary.renameTo(cache)) throw new IllegalStateException("avatar cache write failed");
                }
                Bitmap bitmap = decodeBitmap(cache, decodeSize, decodeSize);
                if (bitmap != null) runOnUiThread(() -> { if (image.isAttachedToWindow() && assetUrl.equals(image.getTag())) image.setImageBitmap(bitmap); });
            } catch (Exception ignored) { }
            finally {
                temporary.delete();
                if (connection != null) connection.disconnect();
            }
        }));
    }

    private void chooseAvatar() {
        new AlertDialog.Builder(this).setTitle(t("choose_avatar")).setItems(new String[]{t("gallery"), t("take_photo")}, (dialog, which) -> {
            if (which == 1) requestCamera(CAMERA_AVATAR);
            else openImagePicker(1001);
        }).show();
    }

    private void openImagePicker(int requestCode) {
        ChatGallery.open(this, "image/*", requestCode);
    }

    private void chooseChatBackground() {
        pendingPicker = 1002; ChatGallery.open(this, "image/*", 1002);
    }

    private JSONArray loadAreaData() {
        if (areaData != null) return areaData;
        try {
            InputStream input = getAssets().open("area.json");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            input.close();
            areaData = new JSONArray(new String(output.toByteArray(), "UTF-8"));
        } catch (Exception ignored) {
            areaData = new JSONArray();
        }
        return areaData;
    }

    private String[] areaNames(JSONArray items) {
        String[] names = new String[items.length()];
        for (int i = 0; i < items.length(); i++) names[i] = items.optJSONObject(i).optString("name");
        return names;
    }

    private JSONArray areaChildren(JSONArray items, int index) {
        JSONObject item = items.optJSONObject(index);
        if (item == null || item.optJSONArray("children") == null) return new JSONArray();
        return item.optJSONArray("children");
    }

    private int areaIndex(JSONArray items, String name) {
        for (int i = 0; i < items.length(); i++) {
            if (name.equals(items.optJSONObject(i).optString("name"))) return i;
        }
        return 0;
    }

    private void showQrDialog(String value) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(28));
        TextView title = centeredText(t("ui_qr_title"), 19, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));
        ImageView qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        qr.setPadding(dp(18), dp(18), dp(18), dp(18));
        qr.setBackgroundColor(Color.WHITE);
        try {
            BitMatrix matrix = new MultiFormatWriter().encode("SPXUSER:" + value, BarcodeFormat.QR_CODE, dp(230), dp(230));
            Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            qr.setImageBitmap(bitmap);
        } catch (Exception ignored) {
            qr.setImageResource(android.R.drawable.ic_menu_help);
        }
        box.addView(qr, new LinearLayout.LayoutParams(dp(260), dp(260)));
        box.addView(centeredText(t("ui_qr_hint"), 14, MUTED));
        box.addView(centeredText(value, 13, BLUE));
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(round(Color.WHITE, 22));
            dialog.getWindow().setGravity(Gravity.CENTER);
        }
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(dp(320), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void openProfileEditPage() {
        LinearLayout root = subPageRoot(t("profile_edit"), t("ui_profile_edit_subtitle"));
        LinearLayout body = column();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(20));
        View editAvatar = userAvatarView(96);
        editAvatar.setOnClickListener(v -> chooseAvatar());
        body.addView(editAvatar, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView avatarHint = centeredText(t("change_avatar"), 13, MUTED);
        body.addView(avatarHint, new LinearLayout.LayoutParams(-1, dp(34)));
        TextView fixedId = bodyText(t("ui_profile_id_fixed", currentUserSpxId), 14, BLUE);
        fixedId.setPadding(dp(14), dp(12), dp(14), dp(12));
        fixedId.setBackground(round(Color.rgb(238, 242, 255), 12));
        fixedId.setOnClickListener(v -> copyText(currentUserSpxId, "SPX ID"));
        body.addView(fixedId);
        EditText nameInput = new EditText(this);
        nameInput.setText(userName);
        nameInput.setHint(t("nickname_input"));
        nameInput.setTextSize(16);
        nameInput.setSingleLine(true);
        nameInput.setPadding(dp(14), 0, dp(14), 0);
        nameInput.setBackground(round(Color.WHITE, 14));
        body.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(54)));
        TextView save = centeredText(t("save_profile"), 15, Color.WHITE);
        save.setTypeface(null, android.graphics.Typeface.BOLD);
        save.setBackground(round(BLUE, 14));
        save.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            JSONObject request = new JSONObject();
            try { if (!name.isEmpty()) request.put("nickname", name); }
            catch (Exception ignored) { return; }
            ApiClient.post(this, "profile.update", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (data instanceof JSONObject) {
                        currentUserSpxId = ((JSONObject) data).optString("spx_id", currentUserSpxId);
                        userName = ((JSONObject) data).optString("nickname", userName);
                        ownProfile().edit().putString("user_name", userName).apply();
                    }
                    closeSubPage();
                    toast(t("profile_saved"));
                }
                @Override public void onError(String message) { toast(message); }
            });
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(48));
        saveParams.setMargins(0, dp(16), 0, 0);
        body.addView(save, saveParams);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void copyText(String value, String label) {
        if (value == null || value.isEmpty()) { toast(t("ui_copy_unavailable", label)); return; }
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        manager.setPrimaryClip(ClipData.newPlainText(label, value));
        toast(t("ui_copied", label));
    }

    private void openEmailRegisterPage() {
        if (!canLeaveAuth()) return;
        if (!ApiClient.token(this).isEmpty()) { toast(t("auth_logout_first")); return; }
        authRegisterMode = true; showAuthenticationPage();
    }

    private ScrollView pageScroll(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return scroll;
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(10), 0, dp(16));
        page.setBackgroundColor(BG);
        return page;
    }

    private class ViewRowsAdapter extends BaseAdapter {
        private final ArrayList<View> rows = new ArrayList<>();

        void replaceRows(LinearLayout source) {
            rows.clear();
            appendRows(source);
        }

        void appendRows(LinearLayout source) {
            while (source.getChildCount() > 0) {
                View child = source.getChildAt(0);
                source.removeViewAt(0);
                rows.add(child);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View item = rows.get(position);
            if (item.getParent() instanceof ViewGroup) ((ViewGroup) item.getParent()).removeView(item);
            return item;
        }
    }

    private ListView createPageList(LinearLayout page, ViewRowsAdapter adapter) {
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        list.setStackFromBottom(false);
        list.setDivider(null);
        list.setBackgroundColor(Color.WHITE);
        list.setCacheColorHint(Color.WHITE);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(16));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return list;
    }

    private void buildChatPage() {
        renderedChatSnapshotSignature = "";
        LinearLayout page = page();
        page.setPadding(0, dp(8), 0, 0);
        page.setBackgroundColor(BG);
        chatHomePage = page;
        chatHomeUserId = currentUserId;
        chatHomeLanguage = appLanguage;
        chatHomeSyncing = false;
        LinearLayout header = row();
        header.setPadding(dp(18), dp(8), dp(18), dp(6));
        LinearLayout heading = column();
        heading.addView(title(t("chats")));
        heading.setPadding(0,dp(2),0,dp(2));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = circleButton("+");
        add.setOnClickListener(v -> showActionSheet());
        header.addView(add);
        page.addView(header);
        EditText searchField = search(t("ui_chat_search"));
        page.addView(searchField);
        LinearLayout filters=row();filters.setPadding(dp(18),dp(6),dp(18),dp(8));
        String[] filterNames={"全部","未读","私聊","群聊"};TextView[] chips=new TextView[4];
        for(int f=0;f<4;f++){final int n=f;TextView chip=centeredText(filterNames[f],13,conversationFilter==f?Color.WHITE:MUTED);chip.setBackground(round(conversationFilter==f?BLUE:Color.WHITE,16));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(36),1);cp.setMargins(dp(3),0,dp(3),0);filters.addView(chip,cp);chips[f]=chip;chip.setOnClickListener(v->{conversationFilter=n;for(int k=0;k<4;k++){chips[k].setTextColor(k==n?Color.WHITE:MUTED);chips[k].setBackground(round(k==n?BLUE:Color.WHITE,16));}if(activeChatRows!=null&&activeChatRowsAdapter!=null)renderChatSnapshot(activeChatRows,readChatSnapshot(),true,searchField.getText().toString().trim(),activeChatRowsAdapter);});}
        page.addView(filters);
        LinearLayout rows = column();
        ViewRowsAdapter rowsAdapter = new ViewRowsAdapter();
        ListView list = createPageList(page, rowsAdapter);
        activeChatListView = list;
        activeChatRows = rows;
        activeChatRowsAdapter = rowsAdapter;
        activeChatSearchField = searchField;
        String cacheKey = accountCacheKey("chat_snapshot");
        String cachedJson = preferences.getString(cacheKey, "");
        JSONArray cached = new JSONArray();
        try { if (!cachedJson.isEmpty()) cached = new JSONArray(cachedJson); } catch (Exception ignored) { }
        if (cached.length() > 0) renderChatSnapshot(rows, cached, false, "", rowsAdapter);
        else { rows.addView(loadingState(t("ui_chat_loading"))); rowsAdapter.replaceRows(rows); }
        int[] searchGeneration = {0};
        Handler searchHandler = new Handler(Looper.getMainLooper());
        Runnable[] pendingSearch = {null};
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String keyword = s.toString().trim();
                renderChatSnapshot(rows, readChatSnapshot(), true, keyword, rowsAdapter);
                int generation = ++searchGeneration[0];
                if (pendingSearch[0] != null) searchHandler.removeCallbacks(pendingSearch[0]);
                if (keyword.length() < 2) return;
                pendingSearch[0] = () -> {
                    Map<String, String> params = new HashMap<>();
                    params.put("q", keyword);
                    ApiClient.get(MainActivity.this, "messages.search", params, new ApiClient.Callback() {
                        @Override public void onSuccess(Object data) {
                            if (activeChatSearchField != searchField || generation != searchGeneration[0] || !keyword.contentEquals(searchField.getText().toString().trim()) || !(data instanceof JSONArray)) return;
                            JSONArray matches = (JSONArray) data;
                            if (matches.length() == 0) return;
                            rows.addView(section(t("ui_message_results")));
                            for (int i = 0; i < matches.length(); i++) {
                                JSONObject item = matches.optJSONObject(i);
                                if (item == null) continue;
                                TextView result = bodyText(item.optString("conversation_name", t("ui_conversation")) + "  ·  " + item.optString("content", ""), 14, TEXT);
                                result.setPadding(dp(18), dp(12), dp(18), dp(12));
                                result.setBackgroundColor(Color.WHITE);
                                result.setOnClickListener(v -> {pendingSearchMessageId=item.optInt("id");openChatWindow(item.optInt("chat_id"), item.optInt("group_id"), item.optString("conversation_name", t("ui_conversation")), t("ui_chat_avatar"), item.optInt("group_id") > 0 ? "group" : "online", "#A8C0FF", "#3F5EFB");});
                                rows.addView(result);
                                rows.addView(separator());
                            }
                            rowsAdapter.appendRows(rows);
                        }
                        @Override public void onError(String message) { }
                    });
                    ApiClient.get(MainActivity.this, "contacts.search", params, new ApiClient.Callback() {
                        @Override public void onSuccess(Object data) {
                            if (activeChatSearchField != searchField || generation != searchGeneration[0] || !keyword.contentEquals(searchField.getText().toString().trim()) || !(data instanceof JSONArray)) return;
                            JSONArray users = (JSONArray) data;
                            if (users.length() == 0) return;
                            rows.addView(section(t("ui_contact_results")));
                            for (int i = 0; i < users.length(); i++) {
                                JSONObject user = users.optJSONObject(i);
                                if (user == null) continue;
                                int id = user.optInt("id");
                                String display = normalizeOptionalText(preferences.getString(remarkCacheKey(id), ""));
                                if (display.isEmpty()) display = normalizeOptionalText(user.optString("remark", ""));
                                if (display.isEmpty()) display = normalizeOptionalText(user.optString("nickname", ""));
                                if (display.isEmpty()) display = t("ui_contact");
                                boolean isOnline = user.optInt("is_online") == 1;
                                addContact(rows, id, display.isEmpty() ? t("ui_contact").substring(0, 1) : display.substring(0, 1), display, t("ui_spx_status", user.optString("spx_id"), isOnline ? t("ui_online") : t("ui_offline")), isOnline ? "online" : "offline", "#A8C0FF", "#3F5EFB", user.optString("spx_id"), user.optString("avatar_url"), false);
                            }
                            rowsAdapter.appendRows(rows);
                        }
                        @Override public void onError(String message) { }
                    });
                };
                searchHandler.postDelayed(pendingSearch[0], 300);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        syncChatHome();
    }

    private void syncChatHome() {
        if (chatHomeSyncing || activeChatRows == null || activeChatRowsAdapter == null || chatHomeUserId != currentUserId) return;
        if (System.currentTimeMillis() - preferences.getLong(accountCacheKey("chat_snapshot_sync"), 0) < 15000L) return;
        chatHomeSyncing = true;
        final int requestUser = currentUserId;
        final long revision = chatSnapshotRevision;
        final LinearLayout rows = activeChatRows;
        final ViewRowsAdapter rowsAdapter = activeChatRowsAdapter;
        final EditText searchField = activeChatSearchField;
        final String cacheKey = chatSnapshotKey();
        final JSONArray cached = readChatSnapshot();
        JSONArray cachedChats = new JSONArray();
        JSONArray cachedGroups = new JSONArray();
        for (int i = 0; i < cached.length(); i++) {
            JSONObject item = cached.optJSONObject(i);
            if (item == null) continue;
            if ("group".equals(item.optString("kind"))) cachedGroups.put(item);
            else cachedChats.put(item);
        }
        JSONArray[] chatRows = {cachedChats};
        JSONArray[] groupRows = {cachedGroups};
        String[] syncErrors = {"", ""};
        boolean[] syncFinished = {false, false};
        Runnable finishSnapshot = () -> {
            if (!syncFinished[0] || !syncFinished[1]) return;
            JSONArray snapshot = new JSONArray();
            for (int i = 0; i < chatRows[0].length(); i++) {
                JSONObject item = chatRows[0].optJSONObject(i);
                if (item == null) continue;
                try { JSONObject row = new JSONObject(item.toString()); row.put("kind", "chat"); snapshot.put(row); }
                catch (Exception ignored) { }
            }
            for (int i = 0; i < groupRows[0].length(); i++) {
                JSONObject item = groupRows[0].optJSONObject(i);
                if (item == null) continue;
                try { JSONObject row = new JSONObject(item.toString()); row.put("kind", "group"); snapshot.put(row); }
                catch (Exception ignored) { }
            }
            String value = snapshot.toString();
            String oldValue = preferences.getString(cacheKey, "");
            if (chatHomeUserId != requestUser || currentUserId != requestUser || activeChatRowsAdapter != rowsAdapter) return;
            chatHomeSyncing = false;
            // A receipt, membership event or realtime message won the race. Never replace it with an older HTTP snapshot.
            if (revision != chatSnapshotRevision) { invalidateChatSnapshot(); syncChatHome(); return; }
            if (!value.equals(oldValue) || snapshot.length() == 0) renderChatSnapshot(rows, snapshot, true, searchField.getText().toString().trim(), rowsAdapter);
            long syncedAt = syncErrors[0].isEmpty() && syncErrors[1].isEmpty() ? System.currentTimeMillis() : 0;
            storeChatSnapshot(snapshot);
            preferences.edit().putLong(accountCacheKey("chat_snapshot_sync"), syncedAt).apply();
            if (snapshot.length() == 0) {
                String state = syncErrors[0].isEmpty() && syncErrors[1].isEmpty() ? t("ui_chat_empty") : t("ui_chat_unavailable");
                rows.addView(syncErrors[0].isEmpty() && syncErrors[1].isEmpty() ? note(state) : retryState(state, () -> { preferences.edit().putLong(accountCacheKey("chat_snapshot_sync"), 0).apply(); syncChatHome(); }));
                rowsAdapter.replaceRows(rows);
            } else if (!syncErrors[0].isEmpty() || !syncErrors[1].isEmpty()) {
                rows.addView(retryState(t("ui_partial_chat_failed"), () -> { preferences.edit().putLong(accountCacheKey("chat_snapshot_sync"), 0).apply(); syncChatHome(); }));
                rowsAdapter.appendRows(rows);
            }
        };
        ApiClient.get(this, "chats", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) { if (data instanceof JSONArray) {chatRows[0]=(JSONArray)data;for(int i=0;i<chatRows[0].length();i++)if(chatRows[0].optJSONObject(i)!=null)markPresenceObserved(chatRows[0].optJSONObject(i));} syncFinished[0] = true; finishSnapshot.run(); }
            @Override public void onError(String message) { syncErrors[0] = message; syncFinished[0] = true; finishSnapshot.run(); }
        });
        ApiClient.get(this, "groups", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) { syncFinished[1] = true; finishSnapshot.run(); return; }
                groupRows[0] = (JSONArray) data;
                syncFinished[1] = true;
                finishSnapshot.run();
            }
            @Override public void onError(String message) { syncErrors[1] = message; syncFinished[1] = true; finishSnapshot.run(); }
        });
    }

    private void renderChatSnapshot(LinearLayout page, JSONArray items, boolean replace, String filter, ViewRowsAdapter adapter) {
        String signature = chatListDisplaySignature(items) + "\nfilter=" + filter + "\nmode=" + conversationFilter;
        signature+="\nlocal_draft="+preferences.getLong("chat_draft_revision",0);
        if (signature.equals(renderedChatSnapshotSignature) && adapter.getCount() > 0) return;
        renderedChatSnapshotSignature = signature;
        final ListView list = activeChatListView;
        final int first = list == null ? 0 : list.getFirstVisiblePosition();
        final View firstView = list == null ? null : list.getChildAt(0);
        final Object anchor = firstView == null ? null : firstView.getTag();
        final int offset = firstView == null ? 0 : firstView.getTop();
        if (replace) {
            page.removeAllViews();
        } else if (page.getChildCount() > 0) {
            page.removeAllViews();
        }
        ArrayList<JSONObject> pinnedChats = new ArrayList<>();
        ArrayList<JSONObject> normalChats = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (item.optInt("pinned", 0) == 1) pinnedChats.add(item);
            else normalChats.add(item);
        }
        Comparator<JSONObject> newestFirst = (left, right) -> {
            int time = conversationTime(right).compareTo(conversationTime(left));
            if (time != 0) return time;
            int kind = left.optString("kind").compareTo(right.optString("kind"));
            return kind != 0 ? kind : Integer.compare(left.optInt("id"), right.optInt("id"));
        };
        pinnedChats.sort(newestFirst);
        normalChats.sort(newestFirst);
        ArrayList<JSONObject> sorted = new ArrayList<>(pinnedChats.size() + normalChats.size());
        sorted.addAll(pinnedChats);
        sorted.addAll(normalChats);
        int unreadTotal = 0;
        for (JSONObject item : sorted) {
            if (item == null) continue;
            int unread = item.optInt("unread_count", 0);
            unreadTotal += unread;
            boolean group = "group".equals(item.optString("kind"));
            if((conversationFilter==1&&unread==0)||(conversationFilter==2&&group)||(conversationFilter==3&&!group))continue;
            String name = normalizeOptionalText(item.optString(group ? "name" : "nickname", ""));
            if (name.isEmpty()) name = normalizeOptionalText(item.optString(group ? "name" : "display_name", ""));
            if (name.isEmpty()) name = normalizeOptionalText(item.optString("title", ""));
            if (name.isEmpty()) name = group ? t("chat_group") : t("ui_conversation");
            String remark = group ? "" : normalizeOptionalText(item.optString("contact_remark", ""));
            if (remark.isEmpty() && !group) remark = normalizeOptionalText(preferences.getString(remarkCacheKey(item.optInt("peer_user_id")), ""));
            if (!group && !remark.isEmpty()) name = remark;
            String last = normalizeOptionalText(item.optString("last_message", ""));
            if("red_packet".equals(item.optString("last_message_type")))last="[SPX001 红包]";
            if("product".equals(item.optString("last_message_type")) && last.startsWith("{"))last=productPreview(last);
            if(last.startsWith("SPXGROUP:"))last=t("group_invitation_message");
            if(group&&item.optInt("mention_unread")>0)last="[有人@我] "+last;
            if (last.isEmpty()) last = t("ui_no_messages");
            String draft=normalizeOptionalText(item.optString("draft",""));
            try{JSONObject local=new JSONObject(preferences.getString("draft_v2:"+ApiClient.baseUrl(this)+":"+currentUserId+":"+(group?"g:":"c:")+item.optInt("id"),"{}"));String value=local.optString("text","");if(!value.isEmpty())draft=value;else if(local.optJSONObject("reply")!=null)draft="[引用消息]";}catch(Exception ignored){}
            if (!draft.isEmpty()) last = t("ui_draft_prefix") + draft;
            if (item.optInt("send_failed", 0) == 1 || "failed".equals(item.optString("delivery_status")))
                last = t("ui_send_failed_prefix") + last;
            else if (item.optInt("sending", 0) == 1 || "sending".equals(item.optString("delivery_status")))
                last = t("ui_sending_prefix") + last;
            String searchValue = (name + " " + remark + " " + item.optString("nickname", "") + " " + last + " " + item.optString("spx_id", "") + " " + item.optString("peer_user_id", "") + " " + item.optString("id", "")).toLowerCase(java.util.Locale.ROOT);
            if (!filter.isEmpty() && !searchValue.contains(filter.toLowerCase(java.util.Locale.ROOT))) continue;
            String time = shortChatTime(conversationTime(item));
            String presenceStatus = presenceOnline(item) ? "online" : "offline";
            unreadCursorByConversation.put((group ? "g:" : "c:") + item.optInt("id"), item.optInt("last_read_message_id", 0));
        addChat(page, group ? 0 : item.optInt("id"), group ? item.optInt("id") : 0,
                    name, group ? groupAvatarInitial(name) : (name.isEmpty() ? t("ui_chat_avatar") : name.substring(0, 1)), last, time,
                    group ? "group" : presenceStatus, unread > 0 ? String.valueOf(unread) : "",
                    group ? "#5B7CFA" : "#A8C0FF", group ? "#9B5DE5" : "#3F5EFB", item.optInt("pinned", 0) == 1,
                    item.optInt("mute", 0) == 1, item.optString("avatar_url", ""), "", group ? item.optInt("member_count", -1) : -1);
            // Stable conversation identity lets the viewport follow the same row when new chats arrive above it.
            if (page.getChildCount() >= 2) {
                String identity = (group ? "group:" : "chat:") + item.optInt("id");
                page.getChildAt(page.getChildCount() - 2).setTag(identity);
                page.getChildAt(page.getChildCount() - 1).setTag(identity + ":divider");
            }
        }
        if (page.getChildCount() == 0 && !filter.isEmpty()) {
            LinearLayout emptyState = column();
            emptyState.setGravity(Gravity.CENTER);
            emptyState.setPadding(dp(24), dp(56), dp(24), dp(56));
            emptyState.addView(centeredText("没有匹配的会话", 16, TEXT));
            TextView hint = centeredText("换个关键词，或切换上方的筛选条件", 13, MUTED);
            hint.setPadding(0, dp(8), 0, 0);
            emptyState.addView(hint);
            page.addView(emptyState);
        }
        adapter.replaceRows(page);
        if (list != null && firstView != null) {
            int position = Math.min(first, Math.max(0, adapter.getCount() - 1));
            if (anchor != null && (first > 0 || offset < 0)) {
                for (int i = 0; i < adapter.getCount(); i++) if (anchor.equals(((View) adapter.getItem(i)).getTag())) { position = i; break; }
            }
            // Apply before the next layout, not in a posted callback after a visible jump.
            list.setSelectionFromTop(position, offset);
        }
        pendingChatUnread = unreadTotal;
        updateChatNavUnread();
    }

    private String conversationTime(JSONObject item) {
        if (item == null) return "";
        String latest = normalizeOptionalText(item.optString("last_message_at", ""));
        String updated = normalizeOptionalText(item.optString("updated_at", ""));
        return latest.compareTo(updated) >= 0 ? latest : updated;
    }

    private String cachedGroupName(int groupId) {
        if (groupId <= 0) return "";
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == groupId) {
                String name = normalizeOptionalText(item.optString("name", ""));
                if (!name.isEmpty()) return name;
            }
        }
        try {
            JSONObject detail = new JSONObject(preferences.getString(groupDetailCacheKey(groupId), "{}"));
            return normalizeOptionalText(detail.optString("name", ""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String chatListDisplaySignature(JSONArray snapshot) {
        ArrayList<String> rows = new ArrayList<>();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item == null) continue;
            JSONArray fields = new JSONArray();
            for (String key : new String[]{"kind","id","peer_user_id","name","nickname","display_name","title","contact_remark","avatar_url","last_message","last_message_type","unread_count","pinned","mute","spx_id","is_online","member_count"}) fields.put(item.optString(key));
            fields.put(preferences.getString(remarkCacheKey(item.optInt("peer_user_id")), ""));
            fields.put(conversationTime(item));
            rows.add(fields.toString());
        }
        java.util.Collections.sort(rows);
        return rows.toString();
    }

    private void refreshVisibleChatList() {
        if (activeTab != 0 || subPageOpen || chatWindowOpen || activeChatRows == null || activeChatRowsAdapter == null) return;
        renderChatSnapshot(activeChatRows, readChatSnapshot(), true,
                activeChatSearchField == null ? "" : activeChatSearchField.getText().toString().trim(), activeChatRowsAdapter);
    }

    private void saveChatListStateBeforeOpen() {
        openedChatListSnapshot = chatListSortSignature(readChatSnapshot());
        if (activeChatListView == null || activeChatListView.getAdapter() == null) return;
        savedChatListPosition = Math.max(0, activeChatListView.getFirstVisiblePosition());
        View first = activeChatListView.getChildAt(0);
        savedChatListOffset = first == null ? 0 : first.getTop();
    }

    private void restoreChatListStateIfUnchanged() {
        if (!restoreChatListPosition || activeChatListView == null) return;
        restoreChatListPosition = false;
        activeChatListView.post(() -> {
            if (activeChatListView != null) activeChatListView.setSelectionFromTop(savedChatListPosition, savedChatListOffset);
        });
    }

    private String chatListSortSignature(JSONArray snapshot) {
        JSONArray signature = new JSONArray();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item == null) continue;
            JSONObject row = new JSONObject();
            try {
                row.put("kind", item.optString("kind", "chat"));
                row.put("id", item.optInt("id"));
                row.put("pinned", item.optInt("pinned"));
                row.put("name", item.optString("name", item.optString("nickname", "")));
                row.put("last_message", item.optString("last_message", ""));
                row.put("last_message_at", conversationTime(item));
                signature.put(row);
            } catch (Exception ignored) { }
        }
        return signature.toString();
    }

    private String normalizeOptionalText(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.isEmpty() || "null".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized)) return "";
        return normalized;
    }

    private JSONArray readCachedArray(String key) {
        try { return new JSONArray(preferences.getString(key, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void markPresenceObserved(JSONObject user) {
        try{user.put("_presence_at",System.currentTimeMillis());}catch(org.json.JSONException invalid){android.util.Log.w("SpxContacts","Invalid presence data");}
    }
    private boolean presenceOnline(JSONObject user) {
        long age=System.currentTimeMillis()-user.optLong("_presence_at",0);
        return user.optInt("is_online")==1 && age>=0 && age<90000;
    }
    private void contactAdded(JSONObject profile) {
        int peer=profile.optInt("id");String key=accountCacheKey("contacts_cache");JSONArray contacts=readCachedArray(key);
        boolean found=false;
        for(int i=0;i<contacts.length();i++)if(contacts.optJSONObject(i)!=null&&contacts.optJSONObject(i).optInt("id")==peer){found=true;break;}
        if(!found)try{JSONObject contact=new JSONObject(profile.toString());contact.put("remark",normalizeOptionalText(profile.optString("contact_remark","")));contacts.put(contact);}catch(org.json.JSONException invalid){android.util.Log.w("SpxContacts","Invalid contact data");}
        preferences.edit().putString(key,contacts.toString()).putLong(accountCacheKey("contacts_sync"),0).apply();
        contactsRevision++;
        if(contactsRender!=null)contactsRender.run();
        invalidateChatSnapshot();
    }

    private void updateCachedContactRemark(int userId, String remark) {
        String key = accountCacheKey("contacts_cache");
        JSONArray contacts = readCachedArray(key);
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact == null || contact.optInt("id") != userId) continue;
            try { contact.put("remark", remark); } catch (Exception ignored) { }
        }
        preferences.edit().putString(key, contacts.toString()).apply();
    }

    private void updateCachedPresence(int userId, boolean online) {
        JSONArray contacts = readCachedArray(accountCacheKey("contacts_cache"));
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact != null && contact.optInt("id") == userId) {
                try { contact.put("is_online", online ? 1 : 0);markPresenceObserved(contact); } catch (Exception ignored) { }
            }
        }
        preferences.edit().putString(accountCacheKey("contacts_cache"), contacts.toString()).apply();
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject conversation = snapshot.optJSONObject(i);
            if (conversation != null && conversation.optInt("peer_user_id") == userId) {
                try { conversation.put("is_online", online ? 1 : 0);markPresenceObserved(conversation); } catch (Exception ignored) { }
            }
        }
        storeChatSnapshot(snapshot);
        if (activeTab == 1 && !subPageOpen && contactsRender!=null) contactsRender.run();
        if(chatWindowOpen && activeGroupId==0 && activePeerUserId==userId && activeChatStatus!=null)activeChatStatus.setText(online?t("ui_online"):t("ui_offline"));
        // Do not rebuild the home page for a presence heartbeat; it causes a visible flash.
    }

    private void removeCachedContact(int userId) {
        String key = accountCacheKey("contacts_cache");
        JSONArray contacts = readCachedArray(key);
        for (int i = contacts.length() - 1; i >= 0; i--) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact != null && contact.optInt("id") == userId) contacts.remove(i);
        }
        preferences.edit().putString(key, contacts.toString()).putLong(accountCacheKey("contacts_sync"),0).apply();
        contactsRevision++;if(contactsRender!=null)contactsRender.run();
    }

    private String userProfileCacheKey(int userId) {
        return accountCacheKey("user_profile") + "_" + userId;
    }

    private JSONObject readCachedUserProfile(int userId) {
        try {
            String value = preferences.getString(userProfileCacheKey(userId), "");
            return value.isEmpty() ? null : new JSONObject(value);
        } catch (Exception ignored) { return null; }
    }

    private void cacheUserProfile(JSONObject profile) {
        if (profile == null || profile.optInt("id") <= 0) return;
        preferences.edit().putString(userProfileCacheKey(profile.optInt("id")), profile.toString()).apply();
    }

    private interface UserProfileCallback {
        void onProfile(JSONObject profile);
        void onError(String message);
    }

    private void loadUserProfile(int userId, UserProfileCallback callback) {
        if (userId <= 0) { callback.onError(t("ui_profile_unavailable")); return; }
        JSONObject cached = readCachedUserProfile(userId);
        long cachedAt = preferences.getLong(userProfileCacheKey(userId) + "_time", 0);
        if (cached != null && System.currentTimeMillis() - cachedAt < 5 * 60 * 1000L) {
            callback.onProfile(cached);
            return;
        }
        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(userId));
        ApiClient.get(this, "user.profile", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) { callback.onError(t("ui_profile_invalid")); return; }
                JSONObject profile = (JSONObject) data;
                markPresenceObserved(profile);
                cacheUserProfile(profile);
                preferences.edit().putLong(userProfileCacheKey(userId) + "_time", System.currentTimeMillis()).apply();
                callback.onProfile(profile);
            }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    private void removeLoadingNote(LinearLayout page) {
        if (page.getChildCount() > 0) page.removeViewAt(page.getChildCount() - 1);
    }

    private String shortChatTime(String value) {
        if (value.length() >= 16) return value.substring(11, 16);
        if (value.length() >= 10) return value.substring(5, 10);
        return value;
    }

    private String formatBeijingMessageTime(String value) {
        String raw = normalizeOptionalText(value);
        if (raw.isEmpty()) return "";
        java.util.TimeZone zone = java.util.TimeZone.getTimeZone("Asia/Shanghai");
        try {
            java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
            parser.setLenient(false);
            parser.setTimeZone(zone);
            java.util.Date sent = parser.parse(raw);
            if (sent == null) return raw;
            java.text.SimpleDateFormat dayFormat = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT);
            dayFormat.setTimeZone(zone);
            java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT);
            timeFormat.setTimeZone(zone);
            String sentDay = dayFormat.format(sent);
            String today = dayFormat.format(new java.util.Date());
            if (sentDay.equals(today)) return timeFormat.format(sent);
            java.util.Calendar yesterday = java.util.Calendar.getInstance(zone);
            yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
            if (sentDay.equals(dayFormat.format(yesterday.getTime()))) return t("ui_yesterday", timeFormat.format(sent));
            java.text.SimpleDateFormat dateTimeFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);
            dateTimeFormat.setTimeZone(zone);
            return dateTimeFormat.format(sent);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String formatAmount(String value) {
        try {
            return new java.math.BigDecimal(value == null || value.isEmpty() ? "0" : value)
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (Exception ignored) { return "0.00"; }
    }

    private String formatUsd(java.math.BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String formatUsdPrice(double value) {
        return formatPrice(String.valueOf(value));
    }
    /** XKRS 单价约 0.00006，MallMoney.price 会显示成 "<0.01"，这里单独保留 6 位小数。 */
    private String formatTokenPrice(String symbol, java.math.BigDecimal price) {
        if (price == null || price.signum() <= 0) return "0";
        if ("XKRS".equals(symbol) && price.compareTo(new java.math.BigDecimal("0.01")) < 0) {
            return price.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
        }
        return formatUsdPrice(price.doubleValue());
    }

    private String formatPrice(String value) {
        return MallMoney.price(value);
    }

    private interface UsdPriceCallback { void onPrice(double price, String error); }

    private double cachedUsdPrice;
    private long cachedUsdPriceAt;
    private ApiClient.SessionStamp priceSession;
    private ArrayList<UsdPriceCallback> priceWaiters;
    private void loadSpx001UsdPrice(UsdPriceCallback callback) {
        long age=android.os.SystemClock.elapsedRealtime()-cachedUsdPriceAt;
        if(priceSession!=null&&priceSession.valid(this)){
            if(cachedUsdPrice>0 && age>=0 && age<15000){callback.onPrice(cachedUsdPrice,"");return;}
            if(priceWaiters!=null){priceWaiters.add(callback);return;}
        }
        final ApiClient.SessionStamp session=new ApiClient.SessionStamp(this);priceSession=session;
        final ArrayList<UsdPriceCallback> waiting=new ArrayList<>();waiting.add(callback);priceWaiters=waiting;
        ApiClient.get(this,"wallet.exchange.quote",null,new ApiClient.Callback(){
            private void finish(double price,String error){
                if(!session.valid(MainActivity.this)||priceSession!=session)return;
                priceWaiters=null;cachedUsdPrice=price;cachedUsdPriceAt=android.os.SystemClock.elapsedRealtime();
                for(UsdPriceCallback listener:waiting)listener.onPrice(price,error);
            }
            public void onSuccess(Object data){double price=data instanceof JSONObject?((JSONObject)data).optDouble("api_price_usd",0):0;finish(price>0?price:0,price>0?"":t("ui_quote_invalid"));}
            public void onError(String message){finish(0,message);}
        });
    }

    private void addChat(LinearLayout parent, String avatar, String name, String message, String time, String status, String unread, String c1, String c2) {
        addChat(parent, 0, 0, name, avatar, message, time, status, unread, c1, c2);
    }

    private void addChat(LinearLayout parent, int chatId, int groupId, String name, String avatar, String message, String time, String status, String unread, String c1, String c2) {
        addChat(parent, chatId, groupId, name, avatar, message, time, status, unread, c1, c2, false);
    }

    private void addChat(LinearLayout parent, int chatId, int groupId, String name, String avatar, String message, String time, String status, String unread, String c1, String c2, boolean pinned) {
        addChat(parent, chatId, groupId, name, avatar, message, time, status, unread, c1, c2, pinned, false, "", "");
    }

    private void addChat(LinearLayout parent, int chatId, int groupId, String name, String avatar, String message, String time, String status, String unread, String c1, String c2, boolean pinned, boolean muted, String avatarUrl, String remark) {
        addChat(parent, chatId, groupId, name, avatar, message, time, status, unread, c1, c2, pinned, muted, avatarUrl, remark, -1);
    }

    private void addChat(LinearLayout parent, int chatId, int groupId, String name, String avatar, String message, String time, String status, String unread, String c1, String c2, boolean pinned, boolean muted, String avatarUrl, String remark, int memberCount) {
        LinearLayout chatRow = createChatRow(chatId, groupId, name, avatar, message, time, status, unread, c1, c2, pinned, muted, avatarUrl, remark, memberCount);
        chatRow.setMinimumHeight(dp(76));
        parent.addView(chatRow, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(separator());
    }

    private LinearLayout createChatRow(int chatId, int groupId, String name, String avatar, String message, String time, String status, String unread, String c1, String c2, boolean pinned, boolean muted, String avatarUrl, String remark, int memberCount) {
        LinearLayout item = row();
        item.setPadding(dp(18), dp(15), dp(18), dp(15));
        item.setBackground(pinned ? stroke(MallUi.TINT, MallUi.LINE, 16, 1) : round(Color.WHITE, 16));
        MallUi.touch(item, 16);
        View avatarView = avatarWithRemoteImage(avatar, avatarUrl, c1, c2, 52);
        item.addView(avatarView);
        LinearLayout info = column();
        info.setPadding(dp(14), 0, 0, 0);
        LinearLayout top = row();
        String title = name;
        // Member counts belong to the group conversation header, not the chat inbox list.
        TextView nameView = bodyText(title, 16, TEXT);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));
        if (pinned) top.addView(statusTag(t("ui_pinned"), BLUE));
        if (muted) top.addView(statusTag(t("ui_muted"), MUTED));
        top.addView(bodyText(time, 12, MUTED));
        info.addView(top);
        LinearLayout messageRow = row();
        String preview = remark.isEmpty() ? message : t("ui_remark", remark) + " · " + message;
        TextView msg = bodyText(preview, 13, Color.rgb(91, 104, 124));
        msg.setSingleLine(true);
        msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        messageRow.addView(msg, new LinearLayout.LayoutParams(0, -2, 1));
        if (!unread.isEmpty() && !"0".equals(unread)) messageRow.addView(badge(unread));
        info.addView(messageRow);
        item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        item.setOnClickListener(v -> {
            if (chatId > 0 || groupId > 0) {
                Integer cursor = unreadCursorByConversation.get((groupId > 0 ? "g:" : "c:") + (groupId > 0 ? groupId : chatId));
                unreadDividerBefore = cursor == null ? 0 : cursor;
                unreadDividerInserted = false;
                openChatWindow(chatId, groupId, name, avatar, status, c1, c2);
            } else {
                openChatWindow(name, avatar, status, status.equals("group"), c1, c2);
            }
        });
        if (chatId > 0 || groupId > 0) item.setOnLongClickListener(v -> {
            showConversationQuickActions(chatId, groupId, name, avatar, status, c1, c2, pinned, muted);
            return true;
        });
        return item;
    }

    private TextView statusTag(String label, int color) {
        TextView tag = centeredText(label, 10, color);
        tag.setPadding(dp(5), dp(2), dp(5), dp(2));
        tag.setBackground(round(Color.rgb(242, 244, 247), 6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(20));
        params.setMargins(0, 0, dp(4), 0);
        tag.setLayoutParams(params);
        return tag;
    }

    private void showConversationQuickActions(int chatId, int groupId, String name, String avatar, String status, String c1, String c2, boolean pinned, boolean muted) {
        String[] actions = {t(pinned ? "ui_unpin" : "ui_pin"), t(muted ? "ui_mute_off" : "ui_mute_on"), t("ui_open_chat")};
        new AlertDialog.Builder(this).setTitle(name).setItems(actions, (dialog, which) -> {
            if (which == 2) {
                openChatWindow(chatId, groupId, name, avatar, status, c1, c2);
                return;
            }
            String key = which == 0 ? "pinned" : "mute";
            boolean value = which == 0 ? !pinned : !muted;
            JSONObject request = new JSONObject();
            try { request.put(groupId > 0 ? "group_id" : "chat_id", groupId > 0 ? groupId : chatId); request.put(key, value ? 1 : 0); }
            catch (Exception ignored) { return; }
            ApiClient.post(this, groupId > 0 ? "groups.settings" : "chats.settings", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    JSONArray snapshot = readChatSnapshot();
                    for (int i = 0; i < snapshot.length(); i++) {
                        JSONObject item = snapshot.optJSONObject(i);
                        if (item == null || item.optInt("id") != (groupId > 0 ? groupId : chatId)) continue;
                        if ((groupId > 0) != "group".equals(item.optString("kind"))) continue;
                        try { item.put(key, value ? 1 : 0); } catch (Exception ignored) { }
                    }
                    storeChatSnapshot(snapshot);
                    invalidateChatSnapshot();
                    if (activeTab == 0 && !chatWindowOpen && !subPageOpen) refreshVisibleChatList();
                     toast(t(which == 0 ? (value ? "ui_pinned_done" : "ui_unpinned_done") : (value ? "ui_mute_enabled" : "ui_mute_disabled")));
                }
                @Override public void onError(String error) { toast(error); }
            });
        }).show();
    }

    private void buildContactsPage() {
        LinearLayout page = page();
        contactsPage=page;contactsRefreshing=false;
        final String[] contactError={""};
        final String[] renderedContacts={""};
        page.setPadding(0, dp(8), 0, 0);
        page.setBackgroundColor(Color.WHITE);
        LinearLayout header = row();
        header.setPadding(dp(18), dp(8), dp(18), dp(6));
        header.addView(title(t("contacts")), new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView refresh=centeredText(t("retry_loading"),12,BLUE);
        refresh.setOnClickListener(v->{preferences.edit().putLong(accountCacheKey("contacts_sync"),0).apply();if(contactsRefresh!=null)contactsRefresh.run();});
        header.addView(refresh,new LinearLayout.LayoutParams(dp(68),dp(48)));
        TextView friend = circleButton("+");
        friend.setOnClickListener(v -> showActionSheet());
        header.addView(friend);
        page.addView(header);
        EditText searchField = search(t("ui_contacts_search"));
        page.addView(searchField);
        LinearLayout rows = column();
        rows.addView(note(t("ui_contacts_loading")));
        ViewRowsAdapter rowsAdapter = new ViewRowsAdapter();
        ListView list = createPageList(page, rowsAdapter);
        rowsAdapter.replaceRows(rows);
        String contactsCacheKey = accountCacheKey("contacts_cache");
        String groupsCacheKey = accountCacheKey("groups_cache");
        boolean hasCachedLists = preferences.contains(contactsCacheKey) || preferences.contains(groupsCacheKey);
        JSONArray[] allContacts = {readCachedArray(contactsCacheKey)};
        JSONArray[] myGroups = {readCachedArray(groupsCacheKey)};
        Runnable renderContacts = () -> {
            if(contactsPage!=page)return;
            int first=list.getFirstVisiblePosition();View anchor=list.getChildAt(0);int top=anchor==null?0:anchor.getTop();
            allContacts[0]=readCachedArray(contactsCacheKey);myGroups[0]=readCachedArray(groupsCacheKey);
            String signature=allContacts[0].toString().replaceAll("\"_presence_at\":\\d+", "\"_presence_at\":0")+myGroups[0].toString()+searchField.getText().toString()+contactError[0];
            for(int i=0;i<allContacts[0].length();i++)if(allContacts[0].optJSONObject(i)!=null)signature+=presenceOnline(allContacts[0].optJSONObject(i))?"1":"0";
            if(signature.equals(renderedContacts[0]))return;renderedContacts[0]=signature;
            rows.removeAllViews();
            rows.addView(section(t("ui_my_contacts")));
            String filter = searchField.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            int count = 0;
            for (int i = 0; i < allContacts[0].length(); i++) {
                JSONObject contact = allContacts[0].optJSONObject(i);
                if (contact == null) continue;
                int contactId = contact.optInt("id");
                String nickname = normalizeOptionalText(contact.optString("nickname", ""));
                if (nickname.isEmpty()) nickname = t("ui_contact");
                String remoteRemark = normalizeOptionalText(contact.optString("remark", ""));
                preferences.edit().putString(remarkCacheKey(contactId), remoteRemark).apply();
                String searchable = (nickname + " " + remoteRemark + " " + contact.optString("spx_id", "") + " " + contactId).toLowerCase(java.util.Locale.ROOT);
                if (!filter.isEmpty() && !searchable.contains(filter)) continue;
                boolean isOnline = presenceOnline(contact);
                addContact(rows, contactId, nickname.isEmpty() ? t("ui_contact").substring(0, 1) : nickname.substring(0, 1), nickname, isOnline ? t("ui_online") : t("ui_offline"), isOnline ? "online" : "offline", "#A8C0FF", "#3F5EFB", contact.optString("spx_id", ""), contact.optString("avatar_url", ""), true, remoteRemark);
                count++;
            }
            if (count == 0 && !filter.isEmpty()) rows.addView(note(t("ui_no_contact_match")));
            rows.addView(section(t("ui_my_groups")));
            int visibleGroups = 0;
            for (int i = 0; i < myGroups[0].length(); i++) {
                JSONObject group = myGroups[0].optJSONObject(i);
                if (group == null) continue;
                String groupName = group.optString("name", t("chat_group"));
                if (!filter.isEmpty() && !groupName.toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
                addContactGroupRow(rows, group);
                visibleGroups++;
            }
            if (visibleGroups == 0) rows.addView(note(filter.isEmpty() ? t("ui_no_groups") : t("ui_no_group_match")));
             TextView total = centeredText(t("ui_contact_total", allContacts[0].length()), 13, MUTED);
            total.setPadding(0, dp(20), 0, dp(12));
            rows.addView(total, new LinearLayout.LayoutParams(-1, dp(48)));
            if(!contactError[0].isEmpty())rows.addView(retryState(t("cache_read_failed_retry",contactError[0]),()->{if(contactsRefresh!=null)contactsRefresh.run();}));
            rowsAdapter.replaceRows(rows);
            if(anchor!=null)list.setSelectionFromTop(first,top);
        };
        contactsRender=renderContacts;
        if (hasCachedLists) renderContacts.run();
        int[] generation = {0};
        Handler searchHandler = new Handler(Looper.getMainLooper());
        Runnable[] pendingSearch = {null};
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderContacts.run();
                String keyword = s.toString().trim();
                int requestId = ++generation[0];
                if (pendingSearch[0] != null) searchHandler.removeCallbacks(pendingSearch[0]);
                if (keyword.length() < 2) return;
                pendingSearch[0] = () -> {
                    Map<String, String> params = new HashMap<>();
                    params.put("q", keyword);
                    ApiClient.get(MainActivity.this, "contacts.search", params, new ApiClient.Callback() {
                        @Override public void onSuccess(Object data) {
                            if (requestId != generation[0] || !keyword.contentEquals(searchField.getText().toString().trim()) || !(data instanceof JSONArray)) return;
                            JSONArray matches = (JSONArray) data;
                            ArrayList<JSONObject> remote = new ArrayList<>();
                            for (int i = 0; i < matches.length(); i++) {
                                JSONObject result = matches.optJSONObject(i);
                                if (result == null) continue;
                                boolean exists = false;
                                for (int j = 0; j < allContacts[0].length(); j++) {
                                    JSONObject contact = allContacts[0].optJSONObject(j);
                                    if (contact != null && contact.optInt("id") == result.optInt("id")) { exists = true; break; }
                                }
                                if (!exists) remote.add(result);
                            }
                            if (remote.isEmpty()) return;
                            rows.addView(section(t("ui_user_results")));
                            for (JSONObject user : remote) {
                                String nickname = normalizeOptionalText(user.optString("nickname", ""));
                                if (nickname.isEmpty()) nickname = t("ui_user");
                                String remark = normalizeOptionalText(user.optString("remark", ""));
                                boolean isOnline = user.optInt("is_online") == 1;
                                addContact(rows, user.optInt("id"), nickname.isEmpty() ? t("ui_user").substring(0, 1) : nickname.substring(0, 1), nickname, t("ui_spx_status", user.optString("spx_id"), isOnline ? t("ui_online") : t("ui_offline")), isOnline ? "online" : "offline", "#A8C0FF", "#3F5EFB", user.optString("spx_id", ""), user.optString("avatar_url", ""), false, remark);
                            }
                            rowsAdapter.appendRows(rows);
                        }
                        @Override public void onError(String message) { }
                    });
                };
                searchHandler.postDelayed(pendingSearch[0], 300);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        contactsRefresh=()->{
            if(contactsPage!=page || contactsRefreshing || ApiClient.token(this).isEmpty())return;
            long age=System.currentTimeMillis()-preferences.getLong(accountCacheKey("contacts_sync"),0);
            if(age>=0 && age<30000 && preferences.contains(contactsCacheKey) && preferences.contains(groupsCacheKey))return;
            contactsRefreshing=true;
            final long revision=contactsRevision;
            int[] remaining={2};String[] errors={"",""};
            Runnable done=()->{
                if(--remaining[0]!=0 || contactsPage!=page)return;
                contactsRefreshing=false;
                if(revision!=contactsRevision){contactsRefresh.run();return;}
                contactError[0]=errors[0].isEmpty()?errors[1]:errors[0];
                if(contactError[0].isEmpty())preferences.edit().putLong(accountCacheKey("contacts_sync"),System.currentTimeMillis()).apply();
                renderContacts.run();
            };
            ApiClient.get(this,"contacts",null,new ApiClient.Callback(){
                public void onSuccess(Object data){
                    if(contactsPage!=page)return;
                    if(revision==contactsRevision && data instanceof JSONArray){
                        JSONArray all=(JSONArray)data;
                        for(int i=0;i<all.length();i++){
                            JSONObject contact=all.optJSONObject(i);if(contact==null)continue;
                            markPresenceObserved(contact);
                            try{JSONObject profile=new JSONObject(contact.toString());profile.put("contact_remark",normalizeOptionalText(contact.optString("remark","")));profile.put("is_contact",1);cacheUserProfile(profile);
                                preferences.edit().putLong(userProfileCacheKey(contact.optInt("id"))+"_time",System.currentTimeMillis()).apply();
                            }catch(org.json.JSONException invalid){android.util.Log.w("SpxContacts","Invalid profile data");}
                        }
                        preferences.edit().putString(contactsCacheKey,all.toString()).apply();
                    }else if(!(data instanceof JSONArray))errors[0]=t("network_response_invalid");
                    done.run();
                }
                public void onError(String message){errors[0]=message;done.run();}
            });
            ApiClient.get(this,"groups",null,new ApiClient.Callback(){
                public void onSuccess(Object data){
                    if(contactsPage!=page)return;
                    if(revision==contactsRevision && data instanceof JSONArray)preferences.edit().putString(groupsCacheKey,data.toString()).apply();
                    else if(!(data instanceof JSONArray))errors[1]=t("network_response_invalid");
                    done.run();
                }
                public void onError(String message){errors[1]=message;done.run();}
            });
        };
        contactsRefresh.run();
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addContactGroupRow(LinearLayout parent, JSONObject group) {
        int groupId = group.optInt("id");
        String name = group.optString("name", t("chat_group"));
        LinearLayout item = row();
        item.setPadding(dp(18), dp(15), dp(18), dp(15));
        item.setBackgroundColor(Color.WHITE);
        item.addView(avatarWithRemoteImage(t("chat_group"), group.optString("avatar_url", ""), "#5B7CFA", "#9B5DE5", 50));
        LinearLayout info = column();
        info.setPadding(dp(14), 0, 0, 0);
        TextView title = bodyText(name, 16, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(title);
        info.addView(bodyText(t("ui_member_count", group.optInt("member_count")), 12, MUTED));
        item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(bodyText("›", 24, Color.rgb(176, 190, 208)));
        item.setOnClickListener(v -> {
            JSONArray snapshot = readChatSnapshot();
            for (int i = snapshot.length() - 1; i >= 0; i--) {
                JSONObject existing = snapshot.optJSONObject(i);
                if (existing != null && "group".equals(existing.optString("kind")) && existing.optInt("id") == groupId) snapshot.remove(i);
            }
            try {
                JSONObject row = new JSONObject(group.toString());
                row.put("kind", "group");
                snapshot.put(row);
                storeChatSnapshot(snapshot);
            } catch (Exception ignored) { }
            openChatWindow(0, groupId, name, t("chat_group"), "group", "#5B7CFA", "#9B5DE5");
        });
        parent.addView(item, new LinearLayout.LayoutParams(-1, dp(74)));
        parent.addView(separator());
    }

    private void addContact(LinearLayout parent, String avatarText, String name, String statusText, String status, String c1, String c2) {
        addContact(parent, 0, avatarText, name, statusText, status, c1, c2, "", "", false);
    }

    private void addContact(LinearLayout parent, int userId, String avatarText, String name, String statusText, String status, String c1, String c2, String spxId, String avatarUrl, boolean isContact) {
        addContact(parent, userId, avatarText, name, statusText, status, c1, c2, spxId, avatarUrl, isContact, "");
    }

    private void addContact(LinearLayout parent, int userId, String avatarText, String name, String statusText, String status, String c1, String c2, String spxId, String avatarUrl, boolean isContact, String contactRemark) {
        LinearLayout item = row();
        item.setPadding(dp(18), dp(15), dp(18), dp(15));
        item.setBackgroundColor(Color.WHITE);
        View avatarView = avatarWithRemoteImage(avatarText, avatarUrl, c1, c2, 50);
        avatarView.setOnClickListener(v -> showContactDetail(userId, name, spxId, avatarUrl, isContact));
        item.addView(avatarView);
        LinearLayout info = column();
        info.setPadding(dp(14), 0, 0, 0);
        TextView nameView = bodyText(name, 16, TEXT);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(nameView);
        info.addView(bodyText("●  " + statusText, 13, statusColor(status)));
        if (!contactRemark.trim().isEmpty()) info.addView(bodyText(t("ui_remark", contactRemark), 12, MUTED));
        item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView details = centeredText(t("ui_details"), 13, BLUE);
        details.setPadding(dp(8), 0, 0, 0);
        details.setOnClickListener(v -> showContactDetail(userId, name, spxId, avatarUrl, isContact));
        item.addView(details, new LinearLayout.LayoutParams(dp(48), dp(48)));
        item.setOnClickListener(v -> {
            if (userId > 0) {
                JSONObject body = new JSONObject();
                try { body.put("peer_user_id", userId); } catch (Exception ignored) { }
                ApiClient.post(MainActivity.this, "chats.create", body, new ApiClient.Callback() {
                    @Override public void onSuccess(Object data) {
                        invalidateChatSnapshot();
                        openChatWindow(data instanceof JSONObject ? ((JSONObject) data).optInt("chat_id") : 0, 0, name, avatarText, status, c1, c2, userId);
                    }
                    @Override public void onError(String message) { toast(message); }
                });
            } else openChatWindow(name, avatarText, status, false, c1, c2);
        });
        parent.addView(item, new LinearLayout.LayoutParams(-1, dp(contactRemark.trim().isEmpty() ? 74 : 88)));
        parent.addView(separator());
    }

    private void showContactDetail(int userId, String nickname, String spxId, String avatarUrl, boolean isContact) {
        if (userId <= 0) { toast(t("ui_profile_unavailable")); return; }
        loadUserProfile(userId, new UserProfileCallback() {
            @Override public void onProfile(JSONObject profile) { openFriendProfilePage(profile); }
            @Override public void onError(String message) { toast(t("ui_profile_load_failed", message)); }
        });
    }

    private void openFriendProfilePage(JSONObject profile) {
        int userId = profile.optInt("id");
        if (userId <= 0) { toast(t("ui_profile_unavailable")); return; }
        cacheUserProfile(profile);
        String nickname = normalizeOptionalText(profile.optString("nickname", ""));
        if (nickname.isEmpty()) nickname = t("ui_spx_user");
        final String displayName = nickname;
        String spxId = normalizeOptionalText(profile.optString("spx_id", ""));
        String avatarUrl = normalizeOptionalText(profile.optString("avatar_url", ""));
        boolean isContact = profile.optInt("is_contact") == 1;
        String remark = normalizeOptionalText(profile.optString("contact_remark", ""));

        LinearLayout root = subPageRoot(t("ui_profile"), t("ui_profile_card"));
        root.setTag("friend-profile:" + userId);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(18), dp(18), dp(18), dp(24));

        LinearLayout identity = column();
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        identity.setPadding(dp(18), dp(24), dp(18), dp(22));
        identity.setBackground(round(Color.WHITE, 12));
        View avatar = avatarWithRemoteImage(displayName.substring(0, 1), avatarUrl, "#A8C0FF", "#3F5EFB", 92);
        identity.addView(avatar, new LinearLayout.LayoutParams(dp(92), dp(92)));
        TextView name = bodyText(displayName, 23, TEXT);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.setMargins(0, dp(14), 0, dp(5));
        identity.addView(name, nameParams);
        TextView spxIdLine = centeredText("SPX ID  " + spxId, 13, MUTED);
        spxIdLine.setOnClickListener(v -> copyText(spxId, "SPX ID"));
        identity.addView(spxIdLine, new LinearLayout.LayoutParams(-1, dp(26)));
        TextView relation = centeredText(isContact ? t("ui_added_friend") : t("ui_spx_user"), 12, isContact ? Color.rgb(31, 126, 86) : BLUE);
        relation.setPadding(dp(10), 0, dp(10), 0);
        relation.setBackground(round(isContact ? Color.rgb(232, 246, 238) : Color.rgb(238, 242, 255), 20));
        LinearLayout.LayoutParams relationParams = new LinearLayout.LayoutParams(-2, dp(30));
        relationParams.topMargin = dp(8);
        identity.addView(relation, relationParams);
        body.addView(identity);

        LinearLayout details = column();
        details.setPadding(dp(16), dp(4), dp(16), dp(4));
        details.setBackground(round(Color.WHITE, 12));
        addProfileFact(details, "SPX ID", spxId.isEmpty() ? t("ui_not_set") : spxId, () -> copyText(spxId, "SPX ID"));
        if (isContact) {
            TextView remarkValue = addProfileFact(details, t("ui_remark_label"), remark.isEmpty() ? t("ui_set_remark") : remark, null);
            remarkValue.setTextColor(BLUE);
            remarkValue.setOnClickListener(v -> showRemarkDialog(remark, remarkValue, remarkCacheKey(userId), userId, false));
        }
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(-1, -2);
        detailsParams.setMargins(0, dp(12), 0, 0);
        body.addView(details, detailsParams);

        if (isContact) {
            LinearLayout callActions = row();
            View voiceCall = profileCallAction("phone", t("ui_voice_call"), userId, false, callActions);
            View videoCall = profileCallAction("video", t("ui_video_call"), userId, true, callActions);
            LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(0, dp(82), 1);
            voiceParams.setMargins(0, dp(12), dp(6), 0);
            callActions.addView(voiceCall, voiceParams);
            LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(0, dp(82), 1);
            videoParams.setMargins(dp(6), dp(12), 0, 0);
            callActions.addView(videoCall, videoParams);
            body.addView(callActions);

            LinearLayout clearHistory = settingRow("⌫", t("ui_clear_friend_history"), true);
            clearHistory.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(t("clear_history"))
                    .setMessage(t("clear_history_warning"))
                    .setNegativeButton(t("cancel"), null)
                    .setPositiveButton(t("clear_records"), (dialog, which) -> clearFriendConversationHistory(userId))
                    .show());
            body.addView(clearHistory, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout deleteFriend = settingRow("−", t("ui_delete_friend"), false);
            ((TextView) deleteFriend.getChildAt(1)).setTextColor(Color.rgb(190, 55, 55));
            deleteFriend.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(t("delete_friend"))
                    .setMessage(t("delete_friend_warning"))
                    .setNegativeButton(t("cancel"), null)
                    .setPositiveButton(t("delete_friend"), (dialog, which) -> deleteFriend(userId))
                    .show());
            body.addView(deleteFriend, new LinearLayout.LayoutParams(-1, -2));
        }

        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView action = centeredText(isContact ? t("ui_send_message") : t("ui_add_contact"), 16, Color.WHITE);
        action.setTypeface(null, android.graphics.Typeface.BOLD);
        action.setBackground(round(BLUE, 10));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(52));
        actionParams.setMargins(dp(18), dp(10), dp(18), dp(18));
        root.addView(action, actionParams);
        action.setOnClickListener(v -> {
            if (!isContact) {
                JSONObject request = new JSONObject();
                try { request.put("spx_id", spxId); } catch (Exception ignored) { return; }
                action.setEnabled(false);
                ApiClient.post(this, "contacts.add", request, new ApiClient.Callback() {
                    @Override public void onSuccess(Object data) {
                        toast(t("ui_added_to_contacts"));
                        try { profile.put("is_contact", 1); } catch (Exception ignored) { }
                        contactAdded(profile);
                        cacheUserProfile(profile);
                        preferences.edit().putLong(userProfileCacheKey(userId) + "_time", System.currentTimeMillis()).apply();
                        loadUserProfile(userId, new UserProfileCallback() {
                            @Override public void onProfile(JSONObject refreshed) { openFriendProfilePage(refreshed); }
                            @Override public void onError(String message) { action.setEnabled(true); toast(message); }
                        });
                    }
                    @Override public void onError(String message) { action.setEnabled(true); toast(message); }
                });
                return;
            }
            JSONObject request = new JSONObject();
            try { request.put("peer_user_id", userId); } catch (Exception ignored) { return; }
            action.setEnabled(false);
            ApiClient.post(this, "chats.create", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    int chatId = data instanceof JSONObject ? ((JSONObject) data).optInt("chat_id") : 0;
                    invalidateChatSnapshot();
                    closeSubPage();
                    openChatWindow(chatId, 0, remark.isEmpty() ? displayName : remark, displayName.substring(0, 1), presenceOnline(profile)?"online":"offline", "#A8C0FF", "#3F5EFB", userId);
                }
                @Override public void onError(String message) { action.setEnabled(true); toast(message); }
            });
        });
        openSubPage(root, activeTab);
    }

    private View profileCallAction(String icon, String label, int peerId, boolean video, LinearLayout actions) {
        LinearLayout card = column(); card.setGravity(Gravity.CENTER);
        card.setBackground(round(Color.WHITE, 14)); card.setContentDescription(label);
        ImageView glyph = new ImageView(this); glyph.setImageDrawable(new ChatControlIcon(icon, BLUE));
        card.addView(glyph, new LinearLayout.LayoutParams(dp(27), dp(27)));
        TextView text = centeredText(label, 14, TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(28)); lp.topMargin = dp(5); card.addView(text, lp);
        card.setOnClickListener(v -> {
            if (Boolean.TRUE.equals(actions.getTag())) return;
            final int owner = currentUserId; final String session = ApiClient.token(this), server = ApiClient.baseUrl(this);
            actions.setTag(true); card.setAlpha(0.55f);
            JSONObject request = new JSONObject(); try { request.put("peer_user_id", peerId); } catch (Exception e) { return; }
            ApiClient.post(this, "chats.create", request, new ApiClient.Callback() {
                public void onSuccess(Object data) {
                    actions.setTag(false); card.setAlpha(1f);
                    if (owner != currentUserId || !session.equals(ApiClient.token(MainActivity.this)) || !server.equals(ApiClient.baseUrl(MainActivity.this)) || !card.isAttachedToWindow()) return;
                    int chatId = data instanceof JSONObject ? ((JSONObject)data).optInt("chat_id") : 0;
                    if (chatId <= 0) { toast("无法建立通话，请重试"); return; }
                    stopActiveVoicePlayback(); ChatCall call = calls(); if (call != null) call.start(chatId, video);
                }
                public void onError(String message) { actions.setTag(false); card.setAlpha(1f); if (owner == currentUserId && card.isAttachedToWindow()) toast(message); }
            });
        }); return card;
    }

    private TextView addProfileFact(LinearLayout parent, String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(13));
        TextView key = bodyText(label, 13, MUTED);
        row.addView(key, new LinearLayout.LayoutParams(dp(88), -2));
        TextView content = bodyText(value, 14, TEXT);
        if (action != null) content.setTextColor(BLUE);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) row.setOnClickListener(v -> action.run());
        parent.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(235, 239, 245));
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return content;
    }

    private void clearFriendConversationHistory(int peerId) {
        JSONObject request = new JSONObject();
        try { request.put("peer_user_id", peerId); } catch (Exception ignored) { return; }
        ApiClient.post(this, "messages.clear.both", request, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                JSONArray chatIds = data instanceof JSONObject ? ((JSONObject) data).optJSONArray("deleted_chat_ids") : null;
                if (chatIds != null) for (int i = 0; i < chatIds.length(); i++) removeRealtimeConversation(chatIds.optInt(i), "history_cleared");
                invalidateChatSnapshot();
                toast(t("ui_history_cleared"));
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void deleteFriend(int peerId) {
        JSONObject request = new JSONObject();
        try { request.put("contact_user_id", peerId); } catch (Exception ignored) { return; }
        ApiClient.post(this, "contacts.delete", request, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                removeCachedContact(peerId);
                preferences.edit().remove(userProfileCacheKey(peerId)).remove(userProfileCacheKey(peerId) + "_time").remove(remarkCacheKey(peerId)).apply();
                JSONArray chatIds = data instanceof JSONObject ? ((JSONObject) data).optJSONArray("deleted_chat_ids") : null;
                if (chatIds != null) for (int i = 0; i < chatIds.length(); i++) removeRealtimeConversation(chatIds.optInt(i), "friend_removed");
                invalidateChatSnapshot();
                toast(t("ui_friend_deleted"));
                loadUserProfile(peerId, new UserProfileCallback() {
                    @Override public void onProfile(JSONObject refreshed) { openFriendProfilePage(refreshed); }
                    @Override public void onError(String message) { renderTab(1); }
                });
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void renderContactDetail(JSONObject profileData) {
        openFriendProfilePage(profileData);
    }

    private void buildEcoPage() {
        LinearLayout page = page();
        page.addView(headerTitle(t("eco_title"), t("eco_subtitle")));
        addEcoCard(page, "◇", t("store"), t("store_desc"), t("open_store"), BLUE, () -> openStorePage());
        addEcoCard(page, "▣", t("staking"), t("staking_desc"), t("view_staking"), Color.rgb(243, 156, 18), () -> openStakingPage());
        addEcoCard(page, "▶", t("video_reward"), t("video_reward_desc"), t("developing"), Color.rgb(32, 154, 112), () -> openAdRewardPage());
        pageScroll(page);
    }

    private void openAdRewardPage() {
        LinearLayout root = subPageRoot(t("video_reward"), t("ad_reward_subtitle"));
        LinearLayout body = column();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(24), dp(54), dp(24), dp(30));
        ImageView icon = new ImageView(this);
        icon.setImageResource(com.spx.chat.R.drawable.wallet_spx001);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setBackground(round(Color.rgb(232, 248, 241), 28));
        body.addView(icon, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView title = bodyText(t("ad_reward_title"), 21, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(28), 0, dp(10));
        body.addView(title);
        TextView status = bodyText(t("loading_ad_points"), 15, MUTED);
        status.setGravity(Gravity.CENTER);
        body.addView(status);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        progressParams.setMargins(0, dp(22), 0, dp(18));
        body.addView(progress, progressParams);
        TextView developing = bodyText(t("ad_reward_developing"), 15, Color.rgb(32, 154, 112));
        developing.setGravity(Gravity.CENTER);
        developing.setLineSpacing(dp(6), 1f);
        developing.setPadding(dp(20), dp(16), dp(20), dp(16));
        developing.setBackground(round(Color.rgb(238, 249, 244), 14));
        body.addView(developing, new LinearLayout.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 2);
        final String[] dots = {"", ".", "..", "..."};
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] tick = {null};
        tick[0] = new Runnable() {
            int index = 0;
            @Override public void run() {
                if (status.getParent() == null) return;
                status.setText(t("loading_ad_points") + dots[index++ % dots.length]);
                handler.postDelayed(this, 600L);
            }
        };
        handler.post(tick[0]);
    }

    private void addEcoCard(LinearLayout parent, String icon, String title, String desc, String badge, int color, Runnable action) {
        LinearLayout card = row();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(MallUi.surface(this, Color.WHITE, 16, true));
        MallUi.touch(card, 16);
        ImageView iconView;
        if (title.equals(t("video_reward"))) {
            iconView = new ImageView(this);
            iconView.setImageResource(com.spx.chat.R.drawable.reward_video);
            iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            String imageUrl = title.equals(t("store"))
                    ? "https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?w=900"
                    : "https://images.unsplash.com/photo-1639762681485-074b7f938ba0?w=900";
            iconView = productImage(imageUrl);
        }
        iconView.setBackground(round(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)), 16));
        iconView.setClipToOutline(true);
        card.addView(iconView, new LinearLayout.LayoutParams(dp(82), dp(82)));
        LinearLayout info = column();
        info.setPadding(dp(16), 0, dp(8), 0);
        TextView titleView = bodyText(title, 19, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(titleView);
        info.addView(bodyText(desc, 13, Color.rgb(108, 122, 142)));
        TextView badgeView = bodyText(badge, 12, color);
        badgeView.setPadding(dp(8), dp(3), dp(8), dp(3));
        badgeView.setBackground(round(Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)), 20));
        info.addView(badgeView);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(bodyText("›", 28, Color.LTGRAY));
        card.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(16), dp(8), dp(16), dp(8));
        parent.addView(card, params);
    }

    private void buildProfilePage() {
        LinearLayout page = page();
        LinearLayout profile = row();
        profile.setPadding(dp(20), dp(22), dp(20), dp(20));
        profile.setBackground(gradient("#182647", "#324D82", 24));
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(-1, -2);
        profileParams.setMargins(dp(16), dp(10), dp(16), dp(8));
        profile.setLayoutParams(profileParams);
        View profileAvatar = userAvatarView(80);
        profileAvatar.setOnClickListener(v -> chooseAvatar());
        profile.addView(profileAvatar, new LinearLayout.LayoutParams(dp(80), dp(80)));
        LinearLayout info = column();
        info.setPadding(dp(18), 0, 0, 0);
        TextView name = bodyText(userName, 23, Color.WHITE);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);
        TextView id = bodyText("▦  " + (currentUserSpxId.isEmpty() ? t("spx_id_loading") : currentUserSpxId), 13, BLUE);
        id.setPadding(dp(10), dp(6), dp(10), dp(6));
        id.setTextColor(MallUi.PRIMARY);
        id.setBackground(round(MallUi.TINT, 24));
        id.setOnClickListener(v -> copyText(currentUserSpxId, "SPX ID"));
        info.addView(id);
        TextView qr = bodyText(t("my_qr"), 12, Color.rgb(211, 223, 255));
        qr.setPadding(0, dp(6), 0, 0);
        qr.setOnClickListener(v -> showQrDialog(currentUserSpxId));
        info.addView(qr);
        profile.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView edit = centeredText(t("edit"), 14, Color.WHITE);
        edit.setBackground(round(0x243F67A0, 12));
        edit.setTypeface(null, android.graphics.Typeface.BOLD);
        edit.setOnClickListener(v -> openProfileEditPage());
        profile.addView(edit, new LinearLayout.LayoutParams(dp(54), dp(44)));
        page.addView(profile);
        page.addView(section(t("account_assets")));
        addProfileMenu(page, "wallet", t("wallet"), t("wallet_menu_desc"), () -> openWalletPage());
        addProfileMenu(page, "orders", t("orders"), t("orders_menu_desc"), () -> openOrdersPage());
        addProfileMenu(page, "store", "商家中心", "申请开店、保证金、商品与订单", () -> openMerchantCenter());
        String addressSummary = addressName.isEmpty() ? t("address_add_summary") : addressName + " · " + addressPhone + " · " + addressProvince + addressCity + addressDistrict;
        addProfileMenu(page, "address", t("my_address"), addressSummary, () -> openAddressPage());
        addProfileMenu(page, "stake", t("staking_records"), t("staking_records_desc"), () -> openStakingPage());
        page.addView(section(t("spx_services")));
        addProfileMenu(page, "invite", t("invite"), t("invite_menu_desc"), () -> openInvitePage());
        addProfileMenu(page, "help", t("ask_title"), t("ask_menu_desc"), () -> openAskWindow());
        addProfileMenu(page, "support", t("support"), t("support_menu_desc"), () -> openHelpPage());
        page.addView(section(t("general")));
        addProfileMenu(page, "settings", t("general_settings"), t("settings_menu_desc"), () -> openSettingsPage());
        try { page.addView(note(t("version_format", getPackageManager().getPackageInfo(getPackageName(), 0).versionName))); } catch (Exception ignored) { }
        pageScroll(page);
    }

    private void addProfileMenu(LinearLayout parent, String icon, String title, String desc, Runnable action) {
        LinearLayout item = row();
        item.setPadding(dp(16), dp(16), dp(16), dp(16));
        item.setBackground(MallUi.surface(this, Color.WHITE, 16, true));
        MallUi.touch(item, 16);
        SimpleIconView iconView = new SimpleIconView(this, icon);
        item.addView(iconView, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout text = column();
        text.setPadding(dp(14), 0, 0, 0);
        TextView titleView = bodyText(title, 16, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        text.addView(titleView);
        TextView description = bodyText(desc, 12, MUTED);
        description.setMaxLines(2);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(description);
        item.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(bodyText("›", 26, Color.rgb(176, 190, 208)));
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(-1, -2);
        menuParams.setMargins(dp(16), 0, dp(16), dp(8));
        parent.addView(item, menuParams);
    }

    private void openChatWindow(String name, String avatarText, String status, boolean group, String c1, String c2) {
        closeChatSocket();
        activeChatId = 0;
        activePeerUserId = 0;
        activeGroupId = 0;
        chatWindowOpen = true;
        setBottomNavVisible(false);
        content.removeAllViews();

        LinearLayout root = column();
        root.setBackgroundColor(BG);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.setBackgroundColor(Color.WHITE);
        TextView back = centeredText("‹", 34, TEXT);
        back.setOnClickListener(v -> closeChatWindow());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout headerInfo = column();
        headerInfo.setPadding(dp(6), 0, 0, 0);
        TextView titleView = bodyText(name, 18, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        headerInfo.addView(titleView);
        headerInfo.addView(bodyText(group ? t("chat_group") : statusLabel(status), 12, statusColor(status)));
        header.addView(headerInfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView detailButton = centeredText("⋯", 26, TEXT);
        detailButton.setOnClickListener(v -> {
            if (group) openGroupDetailPage(name, avatarText, c1, c2);
            else openConversationDetail(name, avatarText, status, false, c1, c2);
        });
        header.addView(detailButton, new LinearLayout.LayoutParams(dp(42), dp(48)));
        root.addView(header);

        ScrollView messages = new ScrollView(this);
        LinearLayout messageList = column();
        messageList.setPadding(dp(12), dp(10), dp(12), dp(14));
        messageList.addView(centeredText(t("demo_time"), 12, MUTED));
        addMessage(messageList, name, avatarText, c1, c2, t("demo_message_incoming"), false);
        addMessage(messageList, t("self_label"), t("self_label"), "#3F5EFB", "#9B5DE5", t("demo_message_reply"), true);
        addMessage(messageList, name, avatarText, c1, c2, t("demo_message_latest"), false);
        applyChatBackground(messages);
        messages.addView(messageList, new ScrollView.LayoutParams(-1, -2));
        root.addView(messages, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = row();
        composer.setPadding(dp(10), dp(8), dp(10), dp(8));
        composer.setBackgroundColor(Color.WHITE);
        TextView composerMore = centeredText("＋", 24, BLUE);
        composerMore.setOnClickListener(v -> showActionSheet());
        composer.addView(composerMore, new LinearLayout.LayoutParams(dp(40), dp(46)));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(t("message_input"));
        input.setTextSize(15);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(round(Color.rgb(240, 242, 245), 24));
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(46), 1));
        activeMessageInput = input;
        TextView send = centeredText(t("send"), 14, BLUE);
        send.setTypeface(null, android.graphics.Typeface.BOLD);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            addMessage(messageList, t("self_label"), t("self_label"), "#3F5EFB", "#9B5DE5", text, true);
            input.setText("");
            messages.post(() -> messages.fullScroll(View.FOCUS_DOWN));
        });
        composer.addView(send, new LinearLayout.LayoutParams(dp(54), dp(46)));
        root.addView(composer);
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));
    }

    private int findChatPeerUserId(int chatId) {
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && !"group".equals(item.optString("kind")) && item.optInt("id") == chatId)
                return item.optInt("peer_user_id");
        }
        return 0;
    }

    private void openChatWindow(int chatId, int groupId, String name, String avatarText, String status, String c1, String c2) {
        openChatWindow(chatId, groupId, name, avatarText, status, c1, c2, findChatPeerUserId(chatId));
    }

    private void openChatWindow(int chatId, int groupId, String name, String avatarText, String status, String c1, String c2, int peerUserId) {
        if (subPageOpen) rememberPage();
        subPageOpen = false;
        currentCommercePage = false;
        saveChatListStateBeforeOpen();
        closeChatSocket();
        pendingOutboxViews.clear();
        activeChatId = chatId;
        activePeerUserId = peerUserId;
        activeGroupId = groupId;
        lastMessageId = 0;
        renderedMessageIds.clear();
        renderedMessageViews.clear();activeMessageModels.clear();activeReplyMessage=null;
        activeMentionUserIds.clear();
        activeMentionNames.clear();
        if(mentionDialog!=null){mentionDialog.dismiss();mentionDialog=null;}
        activeChatName = name;
        activeChatAvatar = avatarText;
        activeChatC1 = c1;
        activeChatC2 = c2;
        chatWindowOpen = true;
        setBottomNavVisible(false);
        content.removeAllViews();

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        LinearLayout header = row();
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.setBackgroundColor(Color.WHITE);
        ImageView back = chatControl("back","返回");
        back.setOnClickListener(v -> closeChatWindow());
        header.addView(back, new LinearLayout.LayoutParams(dp(36), dp(48)));
        header.addView(avatarWithRemoteImage(avatarText,"",c1,c2,38));
        LinearLayout headerInfo = column();
        headerInfo.setPadding(dp(10), 0, dp(6), 0);
        TextView titleView = bodyText(name, 18, TEXT);
        activeChatTitle = titleView;
        updateActiveGroupTitle();
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(groupId > 0 ? android.text.TextUtils.TruncateAt.MIDDLE : android.text.TextUtils.TruncateAt.END);
        headerInfo.addView(titleView);
        activeChatStatus=bodyText(groupId>0?t("chat_group"):"online".equals(status)?t("ui_online"):t("ui_offline"),12,MUTED);
        headerInfo.addView(activeChatStatus);
        header.addView(headerInfo, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView detail = chatControl("detail","聊天详情");
        detail.setOnClickListener(v -> {
            if (groupId > 0) openGroupDetailPage(name, avatarText, c1, c2);
            else openConversationDetail(name, avatarText, status, false, c1, c2);
        });
        header.addView(detail, new LinearLayout.LayoutParams(dp(42), dp(48)));
        root.addView(header);
        root.setFocusableInTouchMode(true);

        ChatMessageScroll messages = new ChatMessageScroll(this);
        messages.setPadding(0, 0, 0, dp(14));
        LinearLayout messageList = column();
        messageList.setPadding(dp(12),dp(10),dp(12),dp(6));
        loadingOlderMessages=false;noOlderMessages=false;reconcilingMessages=false;viewingHistoryContext=false;oldestHistoryCursor=0;chatViewEpoch++;
        olderMessagesButton=centeredText("查看更早消息",12,MUTED);olderMessagesButton.setPadding(0,dp(12),0,dp(12));
        olderMessagesButton.setOnClickListener(v->loadOlderMessages());messageList.addView(olderMessagesButton,new LinearLayout.LayoutParams(-1,dp(44)));
        activeMessageList = messageList;
        activeMessageScroll = messages;
        messageScrollTouched = false;
        messages.setSmoothScrollingEnabled(false);
        messages.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        messages.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_DOWN) messageScrollTouched = true; return false; });
        messages.backgroundTap=()->{
            if(activeMessageScroll!=messages)return;
            if(activeMessageInput!=null){hideChatKeyboard(activeMessageInput);activeMessageInput.clearFocus();}
            if(activeToolPanel!=null)activeToolPanel.setVisibility(View.GONE);
        };
        applyChatBackground(messages);
        messages.addView(messageList, new ScrollView.LayoutParams(-1, -2));
        FrameLayout messageFrame = new FrameLayout(this);
        messageFrame.addView(messages, new FrameLayout.LayoutParams(-1, -1));
        newMessagesButton = centeredText(t("new_messages_view"), 13, Color.WHITE);
        newMessagesButton.setTypeface(null, android.graphics.Typeface.BOLD);
        newMessagesButton.setBackground(round(BLUE, 22));
        newMessagesButton.setVisibility(View.GONE);
        newMessagesButton.setOnClickListener(v -> {if(viewingHistoryContext)returnToLatestChatMessages();else scrollMessagesToBottom();});
        FrameLayout.LayoutParams newMessagesParams = new FrameLayout.LayoutParams(-2, dp(42), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        newMessagesParams.setMargins(dp(12), 0, dp(12), dp(14));
        messageFrame.addView(newMessagesButton, newMessagesParams);
        messages.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (isMessageScrollAtBottom()) {hideNewMessagesButton();markConversationRead(activeChatId,activeGroupId);chatRefreshHandler.removeCallbacks(trimChatWindow);chatRefreshHandler.postDelayed(trimChatWindow,200);}
            if(messageScrollTouched&&scrollY<=dp(28)&&oldScrollY>scrollY)loadOlderMessages();
            if(System.currentTimeMillis()-lastVisibleReconcile>1200){lastVisibleReconcile=System.currentTimeMillis();reconcileVisibleMessages();}
        });
        root.addView(messageFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout toolPanel = column();
        toolPanel.setPadding(dp(16), dp(10), dp(16), dp(10));
        toolPanel.setBackgroundColor(Color.rgb(247, 248, 250));
        toolPanel.setVisibility(View.GONE);
        activeToolPanel = toolPanel;

        TextView replyPreview=bodyText("",12,MUTED);replyPreview.setPadding(dp(16),dp(10),dp(16),dp(10));replyPreview.setBackgroundColor(Color.rgb(239,243,249));replyPreview.setVisibility(View.GONE);
        activeReplyPreview=replyPreview;replyPreview.setOnClickListener(v->{activeReplyMessage=null;updateReplyPreview();saveActiveChatDraft();});root.addView(replyPreview,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout composer = row();
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(8), dp(7), dp(8), dp(7));
        composer.setBackgroundColor(Color.WHITE);
        ImageView voiceMode = chatControl("voice",t("switch_voice_input"));
        composer.addView(voiceMode, new LinearLayout.LayoutParams(dp(42), dp(46)));
        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setMaxHeight(dp(108));
        input.setFilters(new android.text.InputFilter[]{ChatText.FILTER});
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setHint(t("message_input"));
        input.setTextSize(16);
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        input.setBackground(stroke(Color.rgb(245, 247, 251), MallUi.LINE, 10, 1));
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        activeMessageInput = input;
         TextView holdToTalk = centeredText(t("voice_hold"), 15, TEXT);
        holdToTalk.setBackground(round(Color.rgb(240, 242, 245), 20));
        holdToTalk.setVisibility(View.GONE);
        composer.addView(holdToTalk, new LinearLayout.LayoutParams(0, dp(46), 1));
        ImageView emoji=chatControl("emoji","表情");
        composer.addView(emoji, new LinearLayout.LayoutParams(dp(40), dp(46)));
        ImageView more=chatControl("more","更多功能");
        composer.addView(more, new LinearLayout.LayoutParams(dp(40), dp(46)));
        TextView send = centeredText(t("send"), 14, Color.WHITE);
        send.setBackground(round(BLUE,14));
        send.setTypeface(null, android.graphics.Typeface.BOLD);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            sendChatText(text, input, send, messageList, messages, name, avatarText, c1, c2);
        });
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(dp(48),dp(40));sendParams.leftMargin=dp(4);composer.addView(send,sendParams);
         Handler recordingTimer = new Handler(Looper.getMainLooper());
         Runnable[] recordingTick = {null};
         long[] lastRecordingSecond = {-1};
         boolean[] lastRecordingCancelled = {false};
        voiceMode.setOnClickListener(v -> {
            hideChatKeyboard(input);
            boolean voice = holdToTalk.getVisibility() != View.VISIBLE;
            input.setVisibility(voice ? View.GONE : View.VISIBLE);
            holdToTalk.setVisibility(voice ? View.VISIBLE : View.GONE);
            emoji.setVisibility(voice ? View.GONE : View.VISIBLE);
            send.setVisibility(voice||input.getText().toString().trim().isEmpty()?View.GONE:View.VISIBLE);
            more.setVisibility(voice||input.getText().toString().trim().isEmpty()?View.VISIBLE:View.GONE);
            toolPanel.setVisibility(View.GONE);
            voiceMode.setImageDrawable(new ChatControlIcon(voice?"keyboard":"voice",TEXT));
            voiceMode.setContentDescription(voice ? t("switch_text_input") : t("switch_voice_input"));
        });
        holdToTalk.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                voiceRecordingCancelled = false;
                if (startVoiceRecording()) {
                     lastRecordingSecond[0] = -1;
                     lastRecordingCancelled[0] = false;
                    holdToTalk.setText(t("recording") + " 00:00");
                    holdToTalk.setBackground(round(Color.rgb(210, 214, 220), 20));
                    showVoiceOverlay();
                    vibrate(15);
                    recordingTick[0] = new Runnable() {
                        @Override public void run() {
                            if (voiceRecorder == null) return;
                            try {
                                if (voiceWave != null) voiceWave.push(voiceRecorder.getMaxAmplitude());
                            } catch (Exception ignored) { }
                            updateVoiceOverlayTimer((System.currentTimeMillis() - voiceRecordingStartedAt) / 1000L);
                            long seconds = (System.currentTimeMillis() - voiceRecordingStartedAt) / 1000L;
                             boolean cancelled = voiceRecordingCancelled;
                             if (seconds != lastRecordingSecond[0] || cancelled != lastRecordingCancelled[0]) {
                                 holdToTalk.setText(t(cancelled ? "release_cancel_timer" : "recording_timer", String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)));
                                 lastRecordingSecond[0] = seconds;
                                 lastRecordingCancelled[0] = cancelled;
                             }
                            recordingTimer.postDelayed(this, 90);
                        }
                    };
                    recordingTimer.post(recordingTick[0]);
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                boolean wasCancelled = voiceRecordingCancelled;
                voiceRecordingCancelled = event.getY() < -dp(70);
                if (voiceRecordingCancelled != wasCancelled) {
                    if (voiceWave != null) voiceWave.setCancelled(voiceRecordingCancelled);
                    updateVoiceOverlayCancel();
                    if (voiceRecordingCancelled) vibrate(25);
                }
                long seconds = Math.max(0, (System.currentTimeMillis() - voiceRecordingStartedAt) / 1000L);
                 if (seconds != lastRecordingSecond[0] || voiceRecordingCancelled != lastRecordingCancelled[0]) {
                     holdToTalk.setText(t(voiceRecordingCancelled ? "release_cancel_timer" : "continue_recording_timer", String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)));
                     lastRecordingSecond[0] = seconds;
                     lastRecordingCancelled[0] = voiceRecordingCancelled;
                 }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                boolean shouldSend = event.getAction() == MotionEvent.ACTION_UP && !voiceRecordingCancelled;
                if (recordingTick[0] != null) recordingTimer.removeCallbacks(recordingTick[0]);
                hideVoiceOverlay();
                finishVoiceRecording(shouldSend);
                holdToTalk.setText(t("voice_hold"));
                holdToTalk.setBackground(round(Color.rgb(240, 242, 245), 20));
                return true;
            }
            return true;
        });
        emoji.setOnClickListener(v -> {
            if (toolPanel.getVisibility() == View.VISIBLE && "emoji".equals(toolPanel.getTag())) {
                toolPanel.setVisibility(View.GONE);
                return;
            }
            hideChatKeyboard(input);input.setVisibility(View.VISIBLE);holdToTalk.setVisibility(View.GONE);emoji.setVisibility(View.VISIBLE);send.setVisibility(input.getText().toString().trim().isEmpty()?View.GONE:View.VISIBLE);more.setVisibility(input.getText().toString().trim().isEmpty()?View.VISIBLE:View.GONE);
            voiceMode.setImageDrawable(new ChatControlIcon("voice",TEXT));
            showEmojiPanel(toolPanel, value -> {
                int start = Math.max(input.getSelectionStart(), 0);
                int end = Math.max(input.getSelectionEnd(), 0);
                input.getText().replace(Math.min(start, end), Math.max(start, end), value);
                input.requestFocus();
            });
            toolPanel.setTag("emoji");
            toolPanel.setVisibility(View.VISIBLE);
        });
        more.setOnClickListener(v -> {
            if (toolPanel.getVisibility() == View.VISIBLE && "more".equals(toolPanel.getTag())) {
                toolPanel.setVisibility(View.GONE);
                return;
            }
            hideChatKeyboard(input);
            showChatMorePanel(toolPanel);
            toolPanel.setTag("more");
            toolPanel.setVisibility(View.VISIBLE);
        });
        root.addView(composer);
        root.addView(toolPanel);
        restoreActiveChatDraft(input);
        input.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)toolPanel.setVisibility(View.GONE);return false;});
        send.setVisibility(input.getText().toString().trim().isEmpty()?View.GONE:View.VISIBLE);more.setVisibility(input.getText().toString().trim().isEmpty()?View.VISIBLE:View.GONE);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value,int start,int count,int after) {}
            public void onTextChanged(CharSequence value,int start,int before,int count) {
                boolean hasText=!value.toString().trim().isEmpty();send.setEnabled(hasText);send.setVisibility(hasText&&holdToTalk.getVisibility()!=View.VISIBLE?View.VISIBLE:View.GONE);more.setVisibility(hasText&&holdToTalk.getVisibility()!=View.VISIBLE?View.GONE:View.VISIBLE);
                if(!insertingMention&&activeGroupId>0&&count==1&&start<value.length()&&value.charAt(start)=='@')showMentionPicker(input,start);
                if(draftSave!=null)draftHandler.removeCallbacks(draftSave);draftSave=()->{if(activeMessageInput==input)saveActiveChatDraft();};draftHandler.postDelayed(draftSave,250);
            }
            public void afterTextChanged(Editable value) {}
        });
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));
        final int cacheChatId = chatId;
        final int cacheGroupId = groupId;
        final String cacheScope = cacheChatId > 0 ? "chat" : "group";
        final int cacheConversationId = cacheChatId > 0 ? cacheChatId : cacheGroupId;
        // SQLite cache reads can involve up to 200 messages. Keep them off the UI thread so opening a busy chat stays responsive.
        chatCache.execute(() -> {
            final Set<Integer> cachedHidden = new HashSet<>();
            final int[] cachedThrough = {0};
            final JSONArray[] cachedMessages = {new JSONArray()};
            try {
                cachedHidden.addAll(chatCache.hiddenIds(cacheScope, cacheConversationId));
                cachedThrough[0] = chatCache.syncedThrough(cacheScope, cacheConversationId);
                cachedMessages[0] = chatCache.list(cacheScope, cacheConversationId);
            } catch (Exception ignored) {
                // A cache failure must never prevent the network conversation from opening.
            }
            runOnUiThread(() -> {
                if (!chatWindowOpen || activeMessageList != messageList || activeMessageScroll != messages
                        || activeChatId != cacheChatId || activeGroupId != cacheGroupId) return;
                hiddenMessageIds.clear();
                hiddenMessageIds.addAll(cachedHidden);
                historyCursor.seed(cachedThrough[0]);
                for (int i = 0; i < cachedMessages[0].length(); i++) {
                    JSONObject cached = cachedMessages[0].optJSONObject(i);
                    if (cached != null) renderChatMessage(cached, messageList, messages, name, avatarText, c1, c2, false);
                }
                if(pendingSearchMessageId>0)messageScrollTouched=true;

                markConversationRead(cacheChatId, cacheGroupId);
                if (cacheGroupId > 0) showGroupAnnouncementOnEntry(cacheGroupId);
                outbox();renderPendingOutbox();chatOutbox.pump();
                messages.completeInitialLoad(!messageScrollTouched);
                reconcileVisibleMessages();
                if(pendingSearchMessageId>0){int target=pendingSearchMessageId;pendingSearchMessageId=0;locateChatMessage(target);}
                connectChatSocket(messageList, messages, name, avatarText, c1, c2);
            });
        });
    }

    private void connectChatSocket(LinearLayout list, ScrollView scroll, String name, String avatarText, String c1, String c2) {
        activeMessageList = list;
        activeMessageScroll = scroll;
        if(chatSocket==null)startRealtimeSocket();
        loadChatHistory(list,scroll,name,avatarText,c1,c2,chatSocketGeneration);
        scheduleChatHistoryRefresh(list,scroll,name,avatarText,c1,c2);
    }

    private void scheduleChatHistoryRefresh(LinearLayout list,ScrollView scroll,String name,String avatarText,String c1,String c2){
        if (chatHistoryRefresh != null) chatRefreshHandler.removeCallbacks(chatHistoryRefresh);
        chatHistoryRefresh = () -> {
            if (!chatWindowOpen || activeMessageList != list || activeMessageScroll != scroll) return;
            if (appInForeground) {loadChatHistory(list,scroll,name,avatarText,c1,c2,chatSocketGeneration);reconcileVisibleMessages();}
            scheduleChatHistoryRefresh(list,scroll,name,avatarText,c1,c2);
        };
        chatRefreshHandler.postDelayed(chatHistoryRefresh, realtimeReady ? 15000L : 3000L);
    }

    private void startRealtimeSocket() {
        if (chatSocket != null || ApiClient.token(this).isEmpty()) return;
        try {
            int generation = ++chatSocketGeneration;
            Uri base = Uri.parse(ApiClient.connectionBaseUrl(this));
            boolean secure = "https".equalsIgnoreCase(base.getScheme());
            String scheme = secure ? "wss" : "ws";
            String host = base.getHost() == null ? ApiClient.DEFAULT_HOST : base.getHost();
            int port = base.getPort();
            String path = "/ws";
            String url = scheme + "://" + host + (port > 0 ? ":" + port : "") + path;
            final String socketToken=ApiClient.token(this);
            chatSocket = new ChatWebSocket(url, socketToken, new ChatWebSocket.Listener() {
                @Override public void onConnected() {
                    runOnUiThread(() -> {
                        if (generation != chatSocketGeneration) return;
                        realtimeReady = true;
                        preferences.edit().putLong(accountCacheKey("contacts_sync"),0).apply();
                        if(activeTab==1&&!subPageOpen&&contactsRefresh!=null)contactsRefresh.run();
                        invalidateChatSnapshot();syncChatHome();
                        if(chatOutbox!=null&&chatOutbox.belongsTo(currentUserId))chatOutbox.pump();
                        reconcileVisibleMessages();
                        if (chatWindowOpen && activeMessageList != null && activeMessageScroll != null)
                            loadChatHistory(activeMessageList, activeMessageScroll, activeChatName, activeChatAvatar, activeChatC1, activeChatC2, generation);
                    });
                }
                @Override public void onMessage(JSONObject event) {
                    runOnUiThread(() -> {
                        if (generation != chatSocketGeneration) return;
                        int eventChatId = event.optInt("chat_id");
                        int eventGroupId = event.optInt("group_id");
                        String eventType = event.optString("type", "message");
                        if("auth.revoked".equals(eventType)){ApiClient.expireSession(MainActivity.this,socketToken,event.optString("message","账号已在其他设备登录，请重新登录"));return;}
                        if("call.updated".equals(eventType)){if(chatCall!=null)chatCall.event(event);return;}
                        if ("presence.updated".equals(eventType)) { updateCachedPresence(event.optInt("peer_user_id"), event.optBoolean("is_online")); return; }
                        if ("contact.remark.updated".equals(eventType)) { handleContactRemarkUpdate(event.optInt("contact_user_id"), event.optString("remark", "")); return; }
                        if ("profile.updated".equals(eventType)) { handleRemoteProfileUpdate(event); return; }
                        if ("conversation.deleted".equals(eventType)) { removeRealtimeConversation(eventChatId, event.optString("reason", "friend_removed")); return; }
                        if ("contact.deleted".equals(eventType)) {
                            int peerId = event.optInt("peer_user_id");
                            removeCachedContact(peerId);
                            preferences.edit().remove(userProfileCacheKey(peerId)).remove(userProfileCacheKey(peerId) + "_time").remove(remarkCacheKey(peerId)).apply();
                            if (subPageOpen && content != null && content.getChildCount() > 0
                                    && ("friend-profile:" + peerId).equals(content.getChildAt(0).getTag())) {
                                loadUserProfile(peerId, new UserProfileCallback() {
                                    @Override public void onProfile(JSONObject profile) { openFriendProfilePage(profile); }
                                    @Override public void onError(String message) { closeSubPage(); }
                                });
                            }
                            if (activeTab == 1 && !subPageOpen) renderTab(1);
                            return;
                        }
                        if ("group.deleted".equals(eventType) || "group.member_removed".equals(eventType)) { removeRealtimeGroup(eventGroupId); return; }
                        if ("group.joined".equals(eventType)) {
                            invalidateChatSnapshot();
                            refreshGroupInfo(eventGroupId);
                            if (activeTab == 0 && !chatWindowOpen && !subPageOpen) syncChatHome();
                            return;
                        }
                        if ("group.members.updated".equals(eventType)) {
                            updateGroupMemberCount(eventGroupId, event.optInt("member_count", -1));
                            return;
                        }
                        if ("message.deleted".equals(eventType)) { removeRealtimeMessage(event); return; }
                        if ("message.updated".equals(eventType)) { updateRealtimeMessage(event); return; }
                        JSONObject message = event.optJSONObject("message");
                        if (message == null) return;
                        if(ChatRedPacketNotice.parse(message)!=null){
                            final String receiptScope=eventGroupId>0?"group":"chat";final int receiptTarget=eventGroupId>0?eventGroupId:eventChatId;
                            if(receiptTarget<=0)return;
                            chatCache.execute(()->chatCache.put(receiptScope,receiptTarget,message));
                            boolean receiptViewing=chatWindowOpen&&((activeChatId>0&&eventChatId==activeChatId)||(activeGroupId>0&&eventGroupId==activeGroupId));
                            if(receiptViewing&&activeMessageList!=null&&activeMessageScroll!=null){boolean bottom=isMessageScrollAtBottom();renderChatMessage(message,activeMessageList,activeMessageScroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2);if(bottom&&!viewingHistoryContext)scrollMessagesToBottom();}
                            return;
                        }
                        if (message.optInt("sender_id") == currentUserId) {
                            updateCachedConversation(event,message,false);
                            boolean ownViewing=chatWindowOpen&&((activeChatId>0&&eventChatId==activeChatId)||(activeGroupId>0&&eventGroupId==activeGroupId));
                            if(ownViewing&&activeMessageList!=null&&activeMessageScroll!=null){boolean bottom=isMessageScrollAtBottom();renderChatMessage(message,activeMessageList,activeMessageScroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2);if(bottom)scrollMessagesToBottom();}
                            if(activeTab==0&&!chatWindowOpen&&!subPageOpen)refreshVisibleChatList();
                            return;
                        }
                        boolean viewing = appInForeground && chatWindowOpen && ((activeChatId > 0 && eventChatId == activeChatId) || (activeGroupId > 0 && eventGroupId == activeGroupId));
                        boolean fresh = updateCachedConversation(event, message, !viewing || viewingHistoryContext || !isMessageScrollAtBottom());
                        if (viewing && activeMessageList != null && activeMessageScroll != null) {
                            boolean wasAtBottom = isMessageScrollAtBottom();
                            renderChatMessage(message, activeMessageList, activeMessageScroll, activeChatName, activeChatAvatar, activeChatC1, activeChatC2);
                            if (wasAtBottom) scrollMessagesToBottom();
                            else showNewMessagesButton();
                            if (fresh && (isCurrentUserMentioned(message)||!isConversationMuted(eventChatId,eventGroupId))) playIncomingTone(message);
                            if (fresh && isCurrentUserMentioned(message)) toast(t("mentioned_you"));
                            markConversationRead(activeChatId, activeGroupId);
                        } else {
                            /* Unread total was already reconciled by updateCachedConversation. */
                            boolean mentionAlert = isCurrentUserMentioned(message);
                             if (fresh && (mentionAlert || !isConversationMuted(eventChatId, eventGroupId))) {
                                  boolean notified = showChatNotification(message, eventChatId, eventGroupId);
                                  if (!notified && appInForeground) { playIncomingTone(message); showIncomingMessageBanner(message, eventChatId, eventGroupId); }
                                 if (mentionAlert && appInForeground) toast(t("mentioned_you"));
                             }
                            updateChatNavUnread();
                            if (activeTab == 0 && !subPageOpen && !chatWindowOpen) {
                                invalidateChatSnapshot();
                                refreshVisibleChatList();
                            }
                        }
                    });
                }
                @Override public void onDisconnected() { runOnUiThread(() -> {
                    if (generation != chatSocketGeneration) return;
                    realtimeReady = false;
                    if (chatWindowOpen && activeMessageList != null && activeMessageScroll != null)
                        scheduleChatHistoryRefresh(activeMessageList, activeMessageScroll, activeChatName, activeChatAvatar, activeChatC1, activeChatC2);
                }); }
            });
            chatSocket.connect();
        } catch (Exception exception) {
            toast(t("realtime_unavailable"));
        }
    }

    private void markChatRead(int chatId) {
        JSONObject body = new JSONObject();
        try { body.put("chat_id", chatId); } catch (Exception ignored) { return; }
        ApiClient.post(this, "messages.read", body, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) { }
            @Override public void onError(String message) { }
        });
    }

    private void markConversationRead(int chatId, int groupId) {
        if (lastMessageId<=0 || viewingHistoryContext || !appInForeground || !chatWindowOpen || activeChatId != chatId || activeGroupId != groupId || !isMessageScrollAtBottom()) return;
        if (chatId <= 0 && groupId <= 0) return;
        if (pendingReadReceipt != null) readReceiptHandler.removeCallbacks(pendingReadReceipt);
        final int receiptChatId = chatId;
        final int receiptGroupId = groupId;
        final int receiptThrough=lastMessageId;
        pendingReadReceipt = () -> {
            if (viewingHistoryContext || !appInForeground || !chatWindowOpen || activeChatId != receiptChatId || activeGroupId != receiptGroupId || !isMessageScrollAtBottom()) { pendingReadReceipt = null; return; }
            JSONObject body = new JSONObject();
            String action;
            try {
                if (receiptGroupId > 0) {
                    body.put("group_id", receiptGroupId);
                    action = "groups.messages.read";
                } else {
                    body.put("chat_id", receiptChatId);
                    action = "messages.read";
                }
            } catch (Exception ignored) { return; }
            try{body.put("through_id",receiptThrough);}catch(Exception ignored){}
            ApiClient.post(this, action, body, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {if(!viewingHistoryContext&&appInForeground&&chatWindowOpen&&activeChatId==receiptChatId&&activeGroupId==receiptGroupId&&lastMessageId==receiptThrough&&isMessageScrollAtBottom())setCachedConversationUnread(receiptChatId,receiptGroupId,data instanceof JSONObject?((JSONObject)data).optInt("unread_count"):0);}
                @Override public void onError(String message) { }
            });
            pendingReadReceipt = null;
        };
        readReceiptHandler.postDelayed(pendingReadReceipt, 180L);
    }

    private String chatSnapshotKey() { return accountCacheKey("chat_snapshot"); }

    private void invalidateChatSnapshot() {
        preferences.edit().putLong(accountCacheKey("chat_snapshot_sync"), 0).apply();
    }

    private void removeRealtimeConversation(int chatId, String reason) {
        if (chatId <= 0) return;
        chatCache.clear("chat", chatId);
        JSONArray snapshot = readChatSnapshot();
        for (int i = snapshot.length() - 1; i >= 0; i--) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && !"group".equals(item.optString("kind")) && item.optInt("id") == chatId) snapshot.remove(i);
        }
        storeChatSnapshot(snapshot);
        invalidateChatSnapshot();
        if (chatWindowOpen && activeChatId == chatId) {
            closeChatWindow();
            toast(t("history_cleared".equals(reason) ? "history_cleared_short" : "friend_deleted_short"));
        } else if (activeTab == 0 && !subPageOpen && !chatWindowOpen) refreshVisibleChatList();
    }

    private void exitUnavailableGroup() {
        pageHistory.clear();
        activeDetailGroupId = 0;
        subPageOpen = false;
        currentCommercePage = false;
        if (commerce != null) commerce.reset();
        closeChatWindow();
    }

    private void removeRealtimeGroup(int groupId) {
        if (groupId <= 0) return;
        chatCache.clear("group", groupId);
        clearGroupDetailCache(groupId);
        JSONArray snapshot = readChatSnapshot();
        for (int i = snapshot.length() - 1; i >= 0; i--) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == groupId) snapshot.remove(i);
        }
        storeChatSnapshot(snapshot);
        invalidateChatSnapshot();
        if ((chatWindowOpen && activeGroupId == groupId) || (subPageOpen && activeDetailGroupId == groupId)) {
            exitUnavailableGroup();
            toast(t("removed_from_group"));
            return;
        }
        if (activeTab == 0 && !subPageOpen && !chatWindowOpen) refreshVisibleChatList();
    }

    private String remarkCacheKey(int peerId) { return accountCacheKey("remark_user") + "_" + peerId; }

    private void handleContactRemarkUpdate(int peerId, String remark) {
        if (peerId <= 0) return;
        final String savedRemark = normalizeOptionalText(remark);
        if (chatCache != null) chatCache.execute(() -> chatCache.updateContactRemark(peerId, savedRemark));
        preferences.edit().putString(remarkCacheKey(peerId), normalizeOptionalText(remark))
                .remove(userProfileCacheKey(peerId)).remove(userProfileCacheKey(peerId) + "_time").apply();
        updateCachedContactRemark(peerId, normalizeOptionalText(remark));
        if (chatWindowOpen && activeGroupId > 0) {
            for (JSONObject existing : new ArrayList<>(activeMessageModels.values())) {
                try {
                    JSONObject changed = new JSONObject(existing.toString());
                    if (ChatCache.applyContactRemark(changed, peerId, savedRemark)) updateRealtimeMessage(new JSONObject().put("group_id", activeGroupId).put("message", changed));
                } catch (org.json.JSONException ignored) { }
            }
        }
        invalidateChatSnapshot();
        syncChatHome();
        reconcileVisibleMessages();
        refreshGroupRemarkMessages(peerId);
    }

    private void refreshGroupRemarkMessages(int peerId) {
        if (peerId <= 0 || !appInForeground || !chatWindowOpen || activeGroupId <= 0 || activeMessageModels.isEmpty()) return;
        ArrayList<Integer> ids = new ArrayList<>();
        for (JSONObject message : activeMessageModels.values()) {
            if (message == null) continue;
            JSONObject reply = message.optJSONObject("reply");
            if (message.optInt("sender_id") == peerId || (reply != null && (reply.optInt("sender_id") == 0 || reply.optInt("sender_id") == peerId))) {
                int id = message.optInt("id"); if (id > 0) ids.add(id);
            }
        }
        if (ids.isEmpty()) return;
        final int epoch=chatViewEpoch,group=activeGroupId;
        for (int offset=0; offset<ids.size(); offset+=200) {
            StringBuilder csv=new StringBuilder();
            for (int i=offset;i<Math.min(ids.size(),offset+200);i++) { if(csv.length()>0)csv.append(',');csv.append(ids.get(i)); }
            Map<String,String> params=new HashMap<>();params.put("group_id",String.valueOf(group));params.put("ids",csv.toString());
            ApiClient.get(this,"groups.messages.reconcile",params,new ApiClient.Callback(){
                @Override public void onSuccess(Object value){if(epoch!=chatViewEpoch||group!=activeGroupId||!(value instanceof JSONObject))return;JSONArray rows=((JSONObject)value).optJSONArray("messages");if(rows==null)return;for(int i=0;i<rows.length();i++){JSONObject message=rows.optJSONObject(i);if(message==null)continue;try{updateRealtimeMessage(new JSONObject().put("group_id",group).put("message",message));}catch(Exception ignored){}}}
                @Override public void onError(String error){ }
            });
        }
    }

    private String chatDisplayName(JSONObject value, String fallback) {
        if (value == null) return fallback;
        for (String key : new String[]{"display_name", "contact_remark", "nickname"}) {
            String name = normalizeOptionalText(value.optString(key, ""));
            if (!name.isEmpty()) return name;
        }
        return fallback;
    }

    private void handleRemoteProfileUpdate(JSONObject event) {
        int peerId = event.optInt("peer_user_id");
        String avatarUrl = event.optString("avatar_url", "");
        invalidateAvatarCache(avatarUrl);
        JSONObject cachedProfile = readCachedUserProfile(peerId);
        if (cachedProfile != null) {
            try { cachedProfile.put("avatar_url", avatarUrl); } catch (Exception ignored) { }
            cacheUserProfile(cachedProfile);
            preferences.edit().putLong(userProfileCacheKey(peerId) + "_time", System.currentTimeMillis()).apply();
        }
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item == null || item.optInt("peer_user_id") != peerId) continue;
            try { item.put("avatar_url", avatarUrl); } catch (Exception ignored) { }
        }
        storeChatSnapshot(snapshot);
        String contactKey = accountCacheKey("contacts_cache");
        JSONArray contacts = readCachedArray(contactKey);
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact != null && contact.optInt("id") == peerId) {
                try { contact.put("avatar_url", avatarUrl); } catch (Exception ignored) { }
            }
        }
        preferences.edit().putString(contactKey, contacts.toString()).apply();
        invalidateChatSnapshot();
        if (activeTab == 0 && !chatWindowOpen && !subPageOpen) refreshVisibleChatList();
        else if (activeTab == 1 && !subPageOpen) renderTab(1);
        if (!chatWindowOpen || activeChatId <= 0 || peerId <= 0) return;
        boolean activePeer = false;
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && item.optInt("id") == activeChatId && item.optInt("peer_user_id") == peerId) { activePeer = true; break; }
        }
        if (!activePeer) return;
        refreshActiveChatAvatars(activeMessageList, peerId, avatarUrl);
    }

    private void refreshActiveChatAvatars(View view, int peerId, String avatarUrl) {
        if (view == null) return;
        if (("sender:" + peerId).equals(view.getTag()) && view.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view.getParent();
            int index = parent.indexOfChild(view);
            View replacement = avatarWithRemoteImage(activeChatAvatar, avatarUrl, activeChatC1, activeChatC2, 36);
            replacement.setTag(view.getTag());
            ViewGroup.LayoutParams params = view.getLayoutParams();
            parent.removeViewAt(index);
            parent.addView(replacement, index, params);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                refreshActiveChatAvatars(group.getChildAt(i), peerId, avatarUrl);
            }
        }
    }

    private JSONArray readChatSnapshot() {
        try { return new JSONArray(preferences.getString(chatSnapshotKey(), "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void storeChatSnapshot(JSONArray snapshot) {
        int unread = ChatBadge.count(snapshot);
        pendingChatUnread = unread;
        ChatBadge.update(this,currentUserId,snapshot);
        String value = snapshot.toString();
        if (!value.equals(preferences.getString(chatSnapshotKey(), "[]"))) chatSnapshotRevision++;
        preferences.edit().putString(chatSnapshotKey(), value).apply();
        updateChatNavUnread();
    }

    private void setCachedConversationUnread(int chatId, int groupId, int unread) {
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item == null) continue;
            boolean group = "group".equals(item.optString("kind"));
            int id = group ? item.optInt("id") : item.optInt("id");
            if ((group && id == groupId) || (!group && id == chatId)) {
                try { item.put("unread_count", unread);if(unread==0)item.put("mention_unread",0); } catch (Exception ignored) { }
            }
        }
        storeChatSnapshot(snapshot);
    }

    private boolean updateCachedConversation(JSONObject event, JSONObject message, boolean incrementUnread) {
        int chatId = event.optInt("chat_id");
        int groupId = event.optInt("group_id");
        boolean group = groupId > 0;
        JSONArray snapshot = readChatSnapshot();
        JSONObject target = null;
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item == null) continue;
            if ((group && "group".equals(item.optString("kind")) && item.optInt("id") == groupId)
                    || (!group && !"group".equals(item.optString("kind")) && item.optInt("id") == chatId)) {
                target = item;
                break;
            }
        }
        if (target == null) {
            target = new JSONObject();
            try {
                target.put("kind", group ? "group" : "chat");
                target.put("id", group ? groupId : chatId);
                target.put("peer_user_id", message.optInt("sender_id"));
                target.put("nickname", message.optString("nickname", t("new_message")));
                target.put("display_name", message.optString("nickname", t("new_message")));
                String groupName = normalizeOptionalText(message.optString("group_name", ""));
                if (groupName.isEmpty()) groupName = cachedGroupName(groupId);
                target.put("name", groupName.isEmpty() ? t("chat_group") : groupName);
                target.put("unread_count", 0);
                snapshot.put(target);
            } catch (Exception ignored) { return false; }
        }
        String type = message.optString("message_type", "text");
        String preview = "image".equals(type) ? t("ui_image_placeholder") : ("video".equals(type) ? t("ui_video_placeholder") : ("voice".equals(type) ? t("ui_voice_placeholder") : message.optString("content", "")));
        try {
            if("red_packet".equals(type))preview="[SPX001 红包]";
            if("product".equals(type))preview=productPreview(message.optString("content"));
            if(preview.startsWith("SPXGROUP:"))preview=t("group_invitation_message");
            int messageId = message.optInt("id");
            if (messageId > 0 && messageId <= target.optInt("last_message_id")) return false;
            target.put("last_message_id", messageId);
            target.put("last_message", preview);
            target.put("last_message_type", type);
            String now = normalizeOptionalText(message.optString("created_at"));
            if (now.isEmpty()) now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            target.put("last_message_at", now);
            target.put("updated_at", now);
            if (group) {
                String eventGroupName = normalizeOptionalText(message.optString("group_name", ""));
                if (!eventGroupName.isEmpty()) target.put("name", eventGroupName);
            }
            if (incrementUnread) target.put("unread_count", (int)Math.min(Integer.MAX_VALUE,(long)Math.max(0,target.optInt("unread_count",0))+1));
            if(group&&incrementUnread&&isCurrentUserMentioned(message))target.put("mention_unread",messageId);
        } catch (Exception ignored) { }
        storeChatSnapshot(snapshot);
        return true;
    }

    private boolean isConversationMuted(int chatId, int groupId) {
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item == null) continue;
            if (groupId > 0 && "group".equals(item.optString("kind")) && item.optInt("id") == groupId) return item.optInt("mute") == 1;
            if (chatId > 0 && item.optInt("id") == chatId && !"group".equals(item.optString("kind"))) return item.optInt("mute") == 1;
        }
        return false;
    }

    private boolean sameChatMessage(JSONObject a,JSONObject b){
        for(String field:new String[]{"content","message_type","media_url","nickname","display_name","contact_remark","avatar_url","edited_at","created_at","reply","mention_user_ids"})if(!String.valueOf(a.opt(field)).equals(String.valueOf(b.opt(field))))return false;return true;
    }

    private void updateRealtimeMessage(JSONObject event) {
        JSONObject message = event == null ? null : event.optJSONObject("message");
        if (message == null) return;
        boolean eventGroup = event.optInt("group_id") > 0;
        final String eventScope = eventGroup ? "group" : "chat";
        final int eventConversationId = event.optInt(eventGroup ? "group_id" : "chat_id");
        if (eventConversationId <= 0) return;
        chatCache.execute(() -> chatCache.put(eventScope, eventConversationId, message));
        if ((eventGroup && activeGroupId != eventConversationId) || (!eventGroup && activeChatId != eventConversationId)) {
            invalidateChatSnapshot(); refreshChatListIfVisible(); return;
        }
        String key = (activeGroupId > 0 ? "g" : "c") + message.optInt("id");
        JSONObject previous=activeMessageModels.get(key);
        if(previous==null){invalidateChatSnapshot();return;}
        if(previous!=null&&sameChatMessage(previous,message))return;
        View view=renderedMessageViews.remove(key);renderedMessageIds.remove(key);activeMessageModels.remove(key);removeMessageBlock(view);
        if(activeMessageList!=null&&activeMessageScroll!=null)renderChatMessage(message,activeMessageList,activeMessageScroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2,false);
        refreshChatTimeLabels();
        invalidateChatSnapshot();
    }

    private void removeRealtimeMessage(JSONObject event) {
        int id = event.optInt("message_id");
        boolean eventGroup = event.optInt("group_id") > 0;
        final String eventScope = eventGroup ? "group" : "chat";
        final int eventConversationId = event.optInt(eventGroup ? "group_id" : "chat_id");
        if (eventConversationId <= 0 || id <= 0) return;
        chatCache.execute(() -> chatCache.remove(eventScope, eventConversationId, id));
        if ((eventGroup && activeGroupId != eventConversationId) || (!eventGroup && activeChatId != eventConversationId)) {
            invalidateChatSnapshot(); refreshChatListIfVisible(); return;
        }
        String key = (activeGroupId > 0 ? "g" : "c") + id;
        View view = renderedMessageViews.remove(key);
        renderedMessageIds.remove(key);activeMessageModels.remove(key);
        removeMessageBlock(view);
        refreshChatTimeLabels();
        invalidateChatSnapshot();
        refreshChatListIfVisible();
    }

    private void refreshChatListIfVisible() {
        if (activeTab == 0 && !subPageOpen && !chatWindowOpen && bottomNav != null) refreshVisibleChatList();
    }

    private void updateChatNavUnread() {
        if (bottomNav == null || bottomNav.getChildCount() == 0) return;
        TextView tab = (TextView) bottomNav.getChildAt(0);
        tab.setText(t("chats") + (pendingChatUnread > 0 ? " " + Math.min(99, pendingChatUnread) : ""));
        int navColor = pendingChatUnread > 0 ? Color.rgb(205, 56, 78) : (activeTab == 0 ? BLUE : MUTED);
        tab.setTextColor(navColor);
        if (tab.getCompoundDrawables()[1] instanceof AppNavIcon)
            ((AppNavIcon)tab.getCompoundDrawables()[1]).color(navColor);
    }

    private int firstRenderedMessageId(){int oldest=Integer.MAX_VALUE;for(String key:renderedMessageIds){try{oldest=Math.min(oldest,Integer.parseInt(key.substring(1)));}catch(Exception ignored){}}return oldest==Integer.MAX_VALUE?0:oldest;}
    private void loadOlderMessages(){
        if(loadingOlderMessages||noOlderMessages||!chatWindowOpen||activeMessageList==null)return;
        int visibleFirst=firstRenderedMessageId();
        final int epoch=chatViewEpoch,chat=activeChatId,group=activeGroupId,before=oldestHistoryCursor>0?(visibleFirst>0?Math.min(oldestHistoryCursor,visibleFirst):oldestHistoryCursor):visibleFirst;if(before<=0)return;
        final LinearLayout list=activeMessageList;final ScrollView scroll=activeMessageScroll;loadingOlderMessages=true;olderMessagesButton.setText("正在加载…");
        Map<String,String> params=new HashMap<>();params.put(group>0?"group_id":"chat_id",String.valueOf(group>0?group:chat));params.put("before_id",String.valueOf(before));params.put("limit","50");
        ApiClient.get(this,group>0?"groups.messages":"messages",params,new ApiClient.Callback(){
            public void onSuccess(Object value){if(epoch!=chatViewEpoch||activeMessageList!=list)return;loadingOlderMessages=false;
                if(!(value instanceof JSONArray)){olderMessagesButton.setText("加载失败，点击重试");return;}JSONArray rows=(JSONArray)value;JSONArray older=new JSONArray();
                for(int i=0;i<rows.length();i++){JSONObject m=rows.optJSONObject(i);if(m!=null&&m.optInt("id")<before)older.put(m);}
                if(rows.length()>0&&older.length()==0){olderMessagesButton.setText("服务端需更新后才能查看更早消息");return;}
                if(scroll instanceof ChatMessageScroll)((ChatMessageScroll)scroll).stopFollowing();
                final View anchor=list.getChildCount()>1?list.getChildAt(1):null;final int y=scroll.getScrollY(),top=anchor==null?0:anchor.getTop();
                for(int i=0;i<older.length();i++){JSONObject m=older.optJSONObject(i);if(m!=null)renderChatMessage(m,list,scroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2,false);}
                chatCache.execute(()->chatCache.putAll(group>0?"group":"chat",group>0?group:chat,older));
                noOlderMessages=older.length()<50;olderMessagesButton.setText(noOlderMessages?"没有更早消息了":"查看更早消息");
                trimRenderedMessages(true);refreshChatTimeLabels();
                scroll.post(()->{if(epoch==chatViewEpoch&&anchor!=null&&anchor.getParent()==list)scroll.scrollTo(0,y+anchor.getTop()-top);});
            }
            public void onError(String error){if(epoch!=chatViewEpoch)return;loadingOlderMessages=false;olderMessagesButton.setText("加载失败，点击重试");toast(error);}
        });
    }
    private void locateChatMessage(int messageId){
        if(!chatWindowOpen||activeMessageList==null||messageId<=0)return;
        if(activeMessageScroll instanceof ChatMessageScroll)((ChatMessageScroll)activeMessageScroll).stopFollowing();
        String key=(activeGroupId>0?"g":"c")+messageId;View target=renderedMessageViews.get(key);
        if(target!=null){final View found=target;View block=target;while(block.getParent() instanceof View&&block.getParent()!=activeMessageList)block=(View)block.getParent();final View anchor=block;final ScrollView scroll=activeMessageScroll;scroll.post(()->{if(activeMessageScroll!=scroll)return;scroll.scrollTo(0,Math.max(0,anchor.getTop()-dp(80)));found.setAlpha(.45f);found.animate().alpha(1f).setDuration(900).start();});return;}
        final int epoch=chatViewEpoch,chat=activeChatId,group=activeGroupId;Map<String,String> params=new HashMap<>();params.put(group>0?"group_id":"chat_id",String.valueOf(group>0?group:chat));params.put("around_id",String.valueOf(messageId));params.put("limit","40");
        ApiClient.get(this,group>0?"groups.messages":"messages",params,new ApiClient.Callback(){
            public void onSuccess(Object value){
                if(epoch!=chatViewEpoch||!chatWindowOpen||!(value instanceof JSONArray))return;JSONArray rows=(JSONArray)value;boolean found=false;for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null&&rows.optJSONObject(i).optInt("id")==messageId)found=true;
                if(!found){toast("该消息已删除或服务端尚未更新");return;}
                chatViewEpoch++;historyCursor.reset();loadingOlderMessages=false;reconcilingMessages=false;noOlderMessages=false;viewingHistoryContext=true;messageScrollTouched=true;lastMessageId=0;oldestHistoryCursor=0;
                activeMessageList.removeAllViews();renderedMessageIds.clear();renderedMessageViews.clear();activeMessageModels.clear();pendingOutboxViews.clear();
                olderMessagesButton.setText("查看更早消息");activeMessageList.addView(olderMessagesButton,new LinearLayout.LayoutParams(-1,dp(44)));
                for(int i=0;i<rows.length();i++){JSONObject m=rows.optJSONObject(i);if(m!=null)renderChatMessage(m,activeMessageList,activeMessageScroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2,false);}
                showNewMessagesButton();locateChatMessage(messageId);
            }
            public void onError(String error){if(epoch==chatViewEpoch)toast(error);}
        });
    }
    private void reconcileVisibleMessages(){
        if(reconcilingMessages||!appInForeground||!chatWindowOpen||activeMessageList==null)return;
        ArrayList<Integer> ids=new ArrayList<>();for(int i=0;i<activeMessageList.getChildCount();i++){View v=activeMessageList.getChildAt(i);if(v.getTag() instanceof Integer&&v.getBottom()>=activeMessageScroll.getScrollY()&&v.getTop()<=activeMessageScroll.getScrollY()+activeMessageScroll.getHeight())ids.add((Integer)v.getTag());}
        if(ids.isEmpty())return;final int epoch=chatViewEpoch,chat=activeChatId,group=activeGroupId;reconcilingMessages=true;
        StringBuilder csv=new StringBuilder();for(Integer id:ids){if(csv.length()>0)csv.append(",");csv.append(id);}Map<String,String> params=new HashMap<>();params.put(group>0?"group_id":"chat_id",String.valueOf(group>0?group:chat));params.put("ids",csv.toString());
        ApiClient.get(this,group>0?"groups.messages.reconcile":"messages.reconcile",params,new ApiClient.Callback(){
            public void onSuccess(Object value){if(epoch!=chatViewEpoch)return;reconcilingMessages=false;if(!(value instanceof JSONObject))return;JSONObject result=(JSONObject)value;JSONArray rows=result.optJSONArray("messages"),removed=result.optJSONArray("removed_ids");
                if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject m=rows.optJSONObject(i);if(m==null)continue;try{JSONObject event=new JSONObject().put("chat_id",chat).put("group_id",group).put("message",m);updateRealtimeMessage(event);}catch(Exception ignored){}}
                if(removed!=null)for(int i=0;i<removed.length();i++){try{removeRealtimeMessage(new JSONObject().put("chat_id",chat).put("group_id",group).put("message_id",removed.optInt(i)));}catch(Exception ignored){}}
            }
            public void onError(String error){if(epoch==chatViewEpoch)reconcilingMessages=false;}
        });
    }

    private void loadChatHistory(LinearLayout list, ScrollView scroll, String name, String avatarText, String c1, String c2, int generation) {
        if (viewingHistoryContext || !chatWindowOpen || (activeChatId <= 0 && activeGroupId <= 0)) return;
        final int requestEpoch=chatViewEpoch;
        final long ticket = historyCursor.begin();
        if (ticket == 0) return;
        final int requestChat=activeChatId,requestGroup=activeGroupId;
        final int afterId = historyCursor.afterId();
        Map<String, String> params = new HashMap<>();
        params.put(activeGroupId > 0 ? "group_id" : "chat_id", String.valueOf(activeGroupId > 0 ? activeGroupId : activeChatId));
        params.put("after_id", String.valueOf(afterId));
        params.put("limit", afterId>0?"200":"50");
        ApiClient.get(this, activeGroupId > 0 ? "groups.messages" : "messages", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (requestEpoch!=chatViewEpoch || generation != chatSocketGeneration || !chatWindowOpen || activeChatId!=requestChat || activeGroupId!=requestGroup || activeMessageList!=list) { historyCursor.fail(ticket); return; }
                boolean wasAtBottom=isMessageScrollAtBottom();int previousCount=renderedMessageIds.size();
                if (data instanceof JSONArray) {
                    JSONArray messages = (JSONArray) data;
                    int fetchedThrough = afterId;
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject item = messages.optJSONObject(i);
                        if (item != null) fetchedThrough = Math.max(fetchedThrough, item.optInt("id"));
                    }
                    if (!historyCursor.finish(ticket, fetchedThrough)) return;
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject message = messages.optJSONObject(i);
                        if (message == null) continue;
                        renderChatMessage(message, list, scroll, name, avatarText, c1, c2, false);
                    }
                    final int cacheGroup = requestGroup;
                    final int cacheChat = requestChat;
                    final int cacheThrough = fetchedThrough;
                    chatCache.execute(() -> {
                        try {
                            chatCache.putHistoryPage(cacheGroup > 0 ? "group" : "chat", cacheGroup > 0 ? cacheGroup : cacheChat, messages, cacheThrough);
                        } catch (android.database.SQLException ignored) {
                            // Network history remains usable; a later refresh can retry the cache write.
                        }
                    });
                    if(renderedMessageIds.size()>previousCount){if(wasAtBottom)scrollMessagesToBottom();else showNewMessagesButton();markConversationRead(requestChat,requestGroup);}
                    if (afterId>0 && messages.length() == 200 && fetchedThrough > afterId) scroll.post(() -> {
                        if (activeMessageList == list && activeChatId == requestChat && activeGroupId == requestGroup)
                            loadChatHistory(list,scroll,name,avatarText,c1,c2,generation);
                    });
                } else historyCursor.fail(ticket);
            }
            @Override public void onError(String message) { historyCursor.fail(ticket); if(chatWindowOpen&&activeMessageList==list&&list.getChildCount()==0)toast(message); }
        });
    }

    private void renderChatMessage(JSONObject message, LinearLayout list, ScrollView scroll, String name, String avatarText, String c1, String c2) {
        renderChatMessage(message, list, scroll, name, avatarText, c1, c2, true);
    }

    private void renderChatMessage(JSONObject message, LinearLayout list, ScrollView scroll, String name, String avatarText, String c1, String c2, boolean persist) {
        int id = message.optInt("id");
        if(!persist&&id>0)oldestHistoryCursor=oldestHistoryCursor>0?Math.min(oldestHistoryCursor,id):id;
        if(viewingHistoryContext&&persist){final String scope=activeGroupId>0?"group":"chat";final int target=activeGroupId>0?activeGroupId:activeChatId;chatCache.execute(()->chatCache.put(scope,target,message));showNewMessagesButton();return;}
        if(message.optInt("sender_id")==currentUserId&&chatOutbox!=null){String client=message.optString("client_message_id","");if(!client.isEmpty()){chatOutbox.acknowledge(client);removePendingOutboxView(client);}}
        int conversationId = activeGroupId > 0 ? activeGroupId : activeChatId;
        String scope = activeGroupId > 0 ? "group" : "chat";
        if (id <= 0 || hiddenMessageIds.contains(id)) return;
        if(persist&&!viewingHistoryContext){chatRefreshHandler.removeCallbacks(trimChatWindow);chatRefreshHandler.postDelayed(trimChatWindow,250);}
        String key = (activeGroupId > 0 ? "g" : "c") + id;
        if (!renderedMessageIds.add(key)) return;
        LinearLayout targetList = list;
        LinearLayout messageBlock = column();
        if (!unreadDividerInserted && unreadDividerBefore > 0 && id > unreadDividerBefore) {
            unreadDividerInserted = true;
            LinearLayout divider = row();
            divider.setGravity(Gravity.CENTER);
            divider.setPadding(0, dp(8), 0, dp(4));
            TextView dividerLabel = centeredText("以下是新消息", 11, BLUE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-2, -2);
            dividerParams.setMargins(dp(8), 0, dp(8), 0);
            divider.addView(dividerLabel, dividerParams);
            messageBlock.addView(divider, new LinearLayout.LayoutParams(-1, -2));
        }
        messageBlock.setTag(Integer.valueOf(id));
        list = messageBlock;
        if (persist && chatCache != null && (activeChatId > 0 || activeGroupId > 0)) {
            final String cacheScope = activeGroupId > 0 ? "group" : "chat";
            final int cacheConversationId = activeGroupId > 0 ? activeGroupId : activeChatId;
            try {
                final JSONObject cacheMessage = new JSONObject(message.toString());
                chatCache.execute(() -> chatCache.put(cacheScope, cacheConversationId, cacheMessage));
            } catch (Exception ignored) { }
        }
        lastMessageId = Math.max(lastMessageId, id);
        int senderId = message.optInt("sender_id");
        String senderValue = chatDisplayName(message, name);
        if (activeGroupId > 0 && senderId > 0 && senderId != currentUserId && !message.has("contact_remark")) {
            String savedRemark=normalizeOptionalText(preferences.getString(remarkCacheKey(senderId), ""));
            if (!savedRemark.isEmpty()) senderValue=savedRemark;
            else if (preferences.contains(remarkCacheKey(senderId))) senderValue=normalizeOptionalText(message.optString("original_nickname", senderValue));
        }
        final String sender=senderValue;
        if (activeChatId > 0 && senderId > 0 && senderId != currentUserId) activePeerUserId = senderId;
        boolean mine = currentUserId > 0 ? message.optInt("sender_id") == currentUserId : sender.equals(userName);
        activeMessageModels.put(key,message);
        String timestamp = formatBeijingMessageTime(message.optString("created_at", ""));
        if (!timestamp.isEmpty()) {
            TextView timeLabel = centeredText(timestamp, 11, MUTED);
            timeLabel.setTag("message-time");timeLabel.setVisibility(shouldShowChatTime(message)?View.VISIBLE:View.GONE);
            LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-1, dp(24));
            timeParams.setMargins(0, dp(8), 0, dp(3));
            list.addView(timeLabel, timeParams);
        }
        String type = message.optString("message_type", "text");
        JSONObject reply=message.optJSONObject("reply");if(reply!=null)addReplyCard(list,reply,mine);
        if ("system".equals(message.optString("message_origin"))) {
            TextView notice = centeredText(message.optString("content"), 12, MUTED);
            ChatRedPacketNotice receipt=ChatRedPacketNotice.parse(message);
            if(receipt!=null){final int viewer=currentUserId;final String server=ApiClient.baseUrl(this),token=ApiClient.token(this);receipt.bind(notice,viewer,"en".equals(appLanguage),()->{if(!isFinishing()&&!isDestroyed()&&viewer==currentUserId&&server.equals(ApiClient.baseUrl(this))&&token.equals(ApiClient.token(this)))redPackets().detail(receipt.packetId);});}
            notice.setPadding(dp(16), dp(8), dp(16), dp(8));
            list.addView(notice, new LinearLayout.LayoutParams(-1, -2));
            int index = targetList.getChildCount();
            for (int i = 0; i < targetList.getChildCount(); i++) {
                Object tag = targetList.getChildAt(i).getTag();
                if (tag instanceof Integer && ((Integer) tag) > id) { index = i; break; }
            }
            View anchorView = null;
            int anchorTop = 0;
            int oldY = scroll.getScrollY();
            if (!isMessageScrollAtBottom()) for (int i = 0; i < targetList.getChildCount(); i++) {
                View candidate = targetList.getChildAt(i);
                if (candidate.getBottom() > oldY) { anchorView = candidate; anchorTop = candidate.getTop(); break; }
            }
            final View anchorMessage = anchorView;
            final int oldAnchorTop = anchorTop;
            targetList.addView(messageBlock, index, new LinearLayout.LayoutParams(-1, -2));
            if (anchorMessage != null) scroll.post(() -> {
                if (activeMessageScroll == scroll && scroll.getScrollY() == oldY && anchorMessage.getParent() == targetList)
                    scroll.scrollTo(0, oldY + anchorMessage.getTop() - oldAnchorTop);
            });
            renderedMessageViews.put(key, notice);
            return;
        }
        if(activeGroupId>0 || "auto_reply".equals(message.optString("message_origin")) || "scheduled".equals(message.optString("message_origin"))){
            TextView senderLabel=bodyText((activeGroupId>0?sender+"  ":"")+("auto_reply".equals(message.optString("message_origin"))?"自动回复":"scheduled".equals(message.optString("message_origin"))?"定时消息":""),11,MUTED);
            senderLabel.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);senderLabel.setPadding(mine?0:dp(46),dp(5),0,dp(2));list.addView(senderLabel);
        }
        View messageView;
        String content = message.optString("content", "");
        int callMedia = ChatCallRecord.media(message.optString("message_origin"), type, content, activeGroupId);
        if (callMedia != 0) {
             messageView = addCallRecordMessage(list, content, mine, sender, mine ? userName : sender, c1, c2, message.optString("avatar_url", ""), callMedia);
        } else if ("text".equals(type) && content.startsWith("SPXGROUP:")) {
            String[] parts = content.split("\\n", 2);
            String code = parts[0].substring("SPXGROUP:".length()).trim();
            JSONObject invite = new JSONObject();
            try {
                if (parts.length > 1) invite = new JSONObject(parts[1]);
                invite.put("group_code", code);
            } catch (Exception ignored) { }
            messageView = addGroupShareCard(list, mine, sender, message.optString("avatar_url", ""), invite);
        } else if ("mall_card".equals(type)) {
            messageView=addMallOrderCard(list,mine,sender,message.optString("avatar_url",""),message);
        } else if ("red_packet".equals(type)) {
            messageView=addRedPacketCard(list,mine,sender,message.optString("avatar_url",""),content);
        } else if ("product".equals(type)) {
            messageView=addProductCard(list,mine,sender,message.optString("avatar_url", ""),content);
        } else if ("voice".equals(type)) {
             messageView = addVoiceMessage(list, message.optString("media_url", ""), message.optString("content", t("ui_voice_placeholder")), mine, mine ? userName : sender, message.optString("avatar_url", ""));
        } else if ("file".equals(type)) {
             messageView = addFileMessage(list, message.optString("media_url", ""), content, mine, mine ? userName : sender, message.optString("avatar_url", ""));
        } else if ("image".equals(type) || "video".equals(type)) {
             messageView = addMediaMessage(list, message.optString("media_url", ""), type, mine, sender, mine ? userName : sender, c1, c2, message.optString("avatar_url", ""));
            if("scheduled".equals(message.optString("message_origin"))&&!content.isEmpty()&&!"[图片]".equals(content)){TextView caption=addMessage(list,mine?t("self_label"):sender,avatarText,c1,c2,content,mine,message.optString("avatar_url",""));productMessageLinks(caption,content);}
        } else {
             TextView text = addMessage(list, mine ? t("self_label") : sender, mine ? userName : sender, c1, c2, content, mine, message.optString("avatar_url", ""));
            applyQuoteHighlight(text, content);
            if ("text".equals(type)) productMessageLinks(text,content);
            messageView = text;
        }
        int insertAt = targetList.getChildCount();
        for (int i = 0; i < targetList.getChildCount(); i++) {
            Object tag = targetList.getChildAt(i).getTag();
            if (tag instanceof Integer && ((Integer) tag) > id) { insertAt = i; break; }
        }
        View anchorView = null;
        int anchorTop = 0;
        if (!isMessageScrollAtBottom()) for (int i = 0; i < targetList.getChildCount(); i++) {
            View candidate = targetList.getChildAt(i);
            if (candidate.getBottom() > scroll.getScrollY()) { anchorView = candidate; anchorTop = candidate.getTop(); break; }
        }
        final View anchorMessage = anchorView;
        final int oldAnchorTop = anchorTop, oldY = scroll.getScrollY();
        targetList.addView(messageBlock, insertAt, new LinearLayout.LayoutParams(-1, -2));
        if (anchorMessage != null) scroll.post(() -> {
            if (activeMessageScroll == scroll && scroll.getScrollY() == oldY && anchorMessage.getParent() == targetList)
                scroll.scrollTo(0, oldY + anchorMessage.getTop() - oldAnchorTop);
        });
        renderedMessageViews.put(key, messageView);
        if (!mine && messageView.getParent() instanceof ViewGroup) {
            ViewGroup line = (ViewGroup) messageView.getParent();
            if (line.getChildCount() > 1) line.getChildAt(0).setTag("sender:" + senderId);
        }
        if ("text".equals(type) && content.startsWith("SPXGROUP:")) messageView.setOnLongClickListener(v -> true);
        else messageView.setOnLongClickListener(v -> { showMessageActions(message, messageView, mine, "voice".equals(type) || "image".equals(type) || "video".equals(type)); return true; });
        if (activeGroupId > 0 && !mine && senderId > 0 && messageView.getParent() instanceof ViewGroup) {
            ViewGroup line = (ViewGroup) messageView.getParent();
            if (line.getChildCount() > 0) {
                View avatar = line.getChildAt(0);
                avatar.setOnClickListener(v -> showGroupMemberInfo(activeGroupId, senderId));
                avatar.setOnLongClickListener(v -> {
                if (activeMessageInput != null) {
                    insertMention(activeMessageInput,senderId,sender,-1);
                    toast(t("mentioned_prefix") + sender);
                }
                return true;
                });
            }
        }
    }

    private View addCallRecordMessage(LinearLayout parent, String content, boolean mine, String sender,
                                      String avatarText, String c1, String c2, String avatarUrl, int media) {
        LinearLayout line = row();
        line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
        if (!mine) {
            View avatar = avatarWithRemoteImage(avatarText, avatarUrl, c1, c2, 36);
            avatar.setOnClickListener(v -> {
                if (activePeerUserId > 0) showContactDetail(activePeerUserId, sender, "", avatarUrl, true);
                else toast(t("conversation_profile_missing"));
            });
            line.addView(avatar);
        }
        String duration = ChatCallRecord.duration(content, media);
        String statusKey = ChatCallRecord.statusKey(content, media);
        String title = !duration.isEmpty() ? t("call_record_duration", duration)
                : !statusKey.isEmpty() ? t(statusKey) : ChatCallRecord.result(content, media);
        if (title.isEmpty()) title = t("call_record_ended");
        String subtitle = t("call_record_subtitle", t(media == 2 ? "ui_video_call" : "ui_voice_call"),
                t(duration.isEmpty() ? "call_record_redial" : "call_record_call_again"));
        ChatCallBubble bubble = new ChatCallBubble(this, mine, media, !duration.isEmpty(), title, subtitle, BLUE);
        final int recordChat = activeChatId, owner = currentUserId;
        final String token = ApiClient.token(this), server = ApiClient.baseUrl(this);
        bubble.setOnClickListener(v -> {
            if (owner != currentUserId || !token.equals(ApiClient.token(this)) || !server.equals(ApiClient.baseUrl(this))) return;
            showCallRecordRedial(recordChat, media);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(mine ? dp(52) : dp(10), dp(6), mine ? 0 : dp(22), dp(6));
        line.addView(bubble, params);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return bubble;
    }

    private void showCallRecordRedial(int recordChat, int media) {
        if (isFinishing() || isDestroyed() || activeGroupId != 0 || recordChat <= 0 || activeChatId != recordChat || (media != 1 && media != 2)) return;
        if (callRecordRedialPrompt != null && callRecordRedialPrompt.isShowing()) return;
        final int owner = currentUserId;
        final String token = ApiClient.token(this), server = ApiClient.baseUrl(this);
        callRecordRedialPrompt = new AlertDialog.Builder(this)
                .setTitle(t(media == 2 ? "call_record_confirm_video" : "call_record_confirm_voice", activeChatName))
                .setMessage(t("call_record_confirm_hint"))
                .setNegativeButton(t("cancel"), null)
                .setPositiveButton(t(media == 2 ? "call_record_video_action" : "call_record_voice_action"), (dialog, which) -> {
                    if (isFinishing() || isDestroyed() || owner != currentUserId || activeGroupId != 0 || activeChatId != recordChat
                            || !token.equals(ApiClient.token(this)) || !server.equals(ApiClient.baseUrl(this))) return;
                    stopActiveVoicePlayback();
                    ChatCall call = calls();
                    if (call != null) call.start(recordChat, media == 2);
                }).show();
    }

    private View addFileMessage(LinearLayout parent,String url,String name,boolean mine,String avatarText,String avatarUrl){
        JSONObject fileMeta=null;try{if(name!=null&&name.startsWith("SPXFILE:"))fileMeta=new JSONObject(name.substring(8));}catch(Exception ignored){}
        name=fileMessageName(name);
        LinearLayout line=row();line.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);
        if(!mine)line.addView(avatarWithRemoteImage(avatarText,avatarUrl,activeChatC1,activeChatC2,36));
        LinearLayout card=column();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(round(Color.WHITE,12));
        TextView title=bodyText(name==null||name.trim().isEmpty()?"文件":name,14,TEXT);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        long bytes=fileMeta==null?0:fileMeta.optLong("size");
        String sizeText=bytes>0?android.text.format.Formatter.formatShortFileSize(this,bytes)+" · ":"";
        boolean downloaded=url!=null&&!url.isEmpty()&&chatAttachmentCacheFile(url,"file").isFile();
        TextView subtitle=bodyText(sizeText+(url==null||url.isEmpty()?"等待发送":downloaded?"已下载 · 点击打开":"点击下载并打开"),11,MUTED);LinearLayout.LayoutParams sub=new LinearLayout.LayoutParams(-1,-2);sub.topMargin=dp(6);card.addView(title);card.addView(subtitle,sub);
        line.addView(card,new LinearLayout.LayoutParams(dp(228),-2));parent.addView(line,new LinearLayout.LayoutParams(-1,-2));
        if(url!=null&&!url.isEmpty())card.setOnClickListener(v->downloadAndOpenChatFile(url,title,subtitle));
        return card;
    }
    private String fileMessageName(String value){
        String text=value==null?"":value.trim();
        if(text.startsWith("SPXFILE:")){
            try{JSONObject meta=new JSONObject(text.substring("SPXFILE:".length()));String name=meta.optString("name","").trim();if(!name.isEmpty())return name;}catch(Exception ignored){}
        }
        return text.isEmpty()?"文件":text;
    }
    private void downloadAndOpenChatFile(String url,TextView title,TextView subtitle){
        if(!title.isAttachedToWindow())return;
        String segment=Uri.parse(url).getLastPathSegment();if(segment==null||!segment.matches("[A-Za-z0-9_.-]{1,160}")){toast("文件地址无效");return;}
        final String owner=ApiClient.baseUrl(this)+":"+currentUserId;
        File target=ChatOutbox.mediaCacheFile(this,owner,url,"file");File folder=target.getParentFile();if(!folder.isDirectory()&&!folder.mkdirs()){toast("文件存储空间不可用");return;}
        if(target.isFile()&&target.length()>0){openChatFile(target);return;}
        subtitle.setText("正在下载…");cardClickable(title,false);
        ChatMediaWork.execute(target.getAbsolutePath(),()->{HttpURLConnection c=null;File part=new File(target.getAbsolutePath()+".part");try{
            if(!owner.equals(ApiClient.baseUrl(this)+":"+currentUserId))throw new IOException("账号已切换");
            if(!target.isFile()){
                c=ApiClient.openConnection(this,url);c.setConnectTimeout(15000);c.setReadTimeout(120000);int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("下载失败（HTTP "+status+")");
                long total=c.getContentLengthLong(),done=0,last=0;if(total>1024L*1024*1024)throw new IOException("附件超过 1 GB");
                try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(!owner.equals(ApiClient.baseUrl(this)+":"+currentUserId))throw new IOException("账号已切换");done+=n;if(done>1024L*1024*1024)throw new IOException("附件超过 1 GB");out.write(buffer,0,n);long now=System.currentTimeMillis();if(now-last>180){String label=total>0?"下载中 "+Math.min(99,done*100/total)+"%":"正在下载…";title.post(()->subtitle.setText(label));last=now;}}}
                if(done==0||(total>0&&done!=total))throw new IOException("文件下载不完整");if(!part.renameTo(target))throw new IOException("文件保存失败");
            }
            title.post(()->{subtitle.setText("已下载 · 点击打开");cardClickable(title,true);if(title.isAttachedToWindow()&&owner.equals(ApiClient.baseUrl(this)+":"+currentUserId))openChatFile(target);});
        }catch(Exception e){part.delete();title.post(()->{subtitle.setText("下载失败 · 点击重试");cardClickable(title,true);});}finally{if(c!=null)c.disconnect();}});
    }
    private void cardClickable(TextView title,boolean enabled){View card=(View)title.getParent();if(card!=null){card.setEnabled(enabled);card.setAlpha(enabled?1f:0.65f);}}
    private void openChatFile(File file){try{String ext=android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getName());String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file);Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime==null?"application/octet-stream":mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(view,"打开文件"));}catch(Exception e){toast("没有可打开此文件的应用");}}

    private boolean isCurrentUserMentioned(JSONObject message) {
        JSONArray ids = message == null ? null : message.optJSONArray("mention_user_ids");
        if (ids == null) return false;
        for (int i = 0; i < ids.length(); i++) if (ids.optInt(i) == currentUserId || ids.optInt(i) == -1) return true;
        return false;
    }
    private void showMentionPicker(EditText input,int trigger){
        if(mentionDialog!=null&&mentionDialog.isShowing())return;
        final int group=activeGroupId;
        mentionDialog=ChatMentions.show(this,group,currentUserId,(id,name)->{if(activeGroupId==group&&activeMessageInput==input)insertMention(input,id,name,trigger);});
    }
    private void insertMention(EditText input,int id,String name,int trigger){
        if(id==currentUserId||name==null||name.isEmpty())return;
        if(!activeMentionUserIds.contains(id))activeMentionUserIds.add(id);activeMentionNames.put(id,name);
        int position=Math.max(0,input.getSelectionStart());
        insertingMention=true;try{if(trigger>=0&&trigger<input.length()&&input.getText().charAt(trigger)=='@'){input.getText().replace(trigger,trigger+1,"@"+name+" ");input.setSelection(trigger+name.length()+2);}else input.getText().insert(position,"@"+name+" ");}finally{insertingMention=false;}
        input.requestFocus();android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void applyQuoteHighlight(TextView view, String content) {
        if (!content.startsWith(t("quote_prefix"))) return;
        int end = content.indexOf(t("quote_separator"));
        if (end < 0) end = content.indexOf('\n');
        if (end <= 0) return;
        android.text.SpannableString styled = new android.text.SpannableString(content);
        styled.setSpan(new android.text.style.BackgroundColorSpan(Color.rgb(227, 231, 238)), 0, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(69, 78, 92)), 0, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(styled);
    }

    private View addGroupShareCard(LinearLayout parent, boolean mine, String senderName, String senderAvatarUrl, JSONObject group) {
        String code = group.optString("group_code", "");
        String groupName = group.optString("name", t("chat_group"));
        String avatarUrl = group.optString("avatar_url", "");
        int memberCount = group.optInt("member_count");
        LinearLayout line = row();
        line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
        if (!mine) line.addView(avatarWithRemoteImage(senderName.isEmpty() ? t("ui_chat_avatar") : senderName.substring(0, 1), senderAvatarUrl, "#A8C0FF", "#3F5EFB", 36));

        LinearLayout card = column();
        card.setPadding(dp(12), dp(12), dp(12), dp(10));
        card.setBackground(stroke(Color.WHITE, Color.rgb(218, 224, 235), 12, 1));
        LinearLayout details = row();
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(avatarWithRemoteImage(t("ui_group_avatar"), avatarUrl, "#5B7CFA", "#9B5DE5", 50));
        LinearLayout text = column();
        text.setPadding(dp(11), 0, 0, 0);
        TextView title = bodyText(groupName, 16, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxWidth(dp(190));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        text.addView(bodyText(t("group_member_code", memberCount, code), 12, MUTED));
        details.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(details);
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(235, 238, 243));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(10), 0, dp(8));
        card.addView(divider, dividerParams);
        TextView action = bodyText(t("view_join_group") + "  ›", 13, BLUE);
        action.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(action);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(Math.min(dp(310), getResources().getDisplayMetrics().widthPixels - dp(108)), -2);
        cardParams.setMargins(mine ? dp(52) : dp(9), dp(5), mine ? 0 : dp(52), dp(5));
        line.addView(card, cardParams);
        card.setOnClickListener(v -> joinGroupByCode(code));
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void closeChatSocket() {
        saveActiveChatDraft();
        if(activeMessageInput!=null)hideChatKeyboard(activeMessageInput);activeToolPanel=null;activeReplyPreview=null;pendingOutboxViews.clear();
        chatViewEpoch++;loadingOlderMessages=false;reconcilingMessages=false;
        if(pendingReadReceipt!=null)readReceiptHandler.removeCallbacks(pendingReadReceipt);pendingReadReceipt=null;
        historyCursor.reset();
        if (chatHistoryRefresh != null) chatRefreshHandler.removeCallbacks(chatHistoryRefresh);
        chatRefreshHandler.removeCallbacks(trimChatWindow);
        chatHistoryRefresh = null;
        activeChatTitle = null;
        activeMessageInput = null;
        activeMessageList = null;
        activeMessageScroll = null;
        newMessagesButton = null;
        incomingBannerHandler.removeCallbacks(hideIncomingBanner);
        hideIncomingBanner.run();
    }

    private boolean isMessageScrollAtBottom() {
        if (activeMessageScroll == null || activeMessageScroll.getChildCount() == 0) return true;
        View child = activeMessageScroll.getChildAt(0);
        int distance = activeMessageScroll instanceof ChatMessageScroll
                ? ((ChatMessageScroll) activeMessageScroll).bottomDistance()
                : child.getBottom() - (activeMessageScroll.getScrollY() + activeMessageScroll.getHeight());
        return distance <= dp(80);
    }

    private void showNewMessagesButton() {
        if (newMessagesButton != null) {newMessagesButton.setText(viewingHistoryContext?"返回最新消息":t("new_messages_view"));newMessagesButton.setVisibility(View.VISIBLE);}
    }

    private void hideNewMessagesButton() {
        if(viewingHistoryContext){showNewMessagesButton();return;}
        if (newMessagesButton != null) newMessagesButton.setVisibility(View.GONE);
    }
    private void returnToLatestChatMessages(){
        if(!chatWindowOpen)return;openChatWindow(activeChatId,activeGroupId,activeChatName,activeChatAvatar,"",activeChatC1,activeChatC2,activePeerUserId);
    }

    private void scrollMessagesToBottom() {
        if(viewingHistoryContext){showNewMessagesButton();return;}
        if (activeMessageScroll == null) return;
        final ScrollView scroll = activeMessageScroll;
        if (scroll instanceof ChatMessageScroll) {
            ((ChatMessageScroll) scroll).requestBottom();
            hideNewMessagesButton();
            markConversationRead(activeChatId,activeGroupId);
        } else scroll.post(() -> {
            if (activeMessageScroll != scroll || scroll.getChildCount() == 0) return;
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            hideNewMessagesButton();
            markConversationRead(activeChatId,activeGroupId);
        });
    }

    private void trimRenderedMessages(boolean olderPage){
        if(activeMessageList==null||renderedMessageIds.size()<=300||(!olderPage&&(!isMessageScrollAtBottom()||viewingHistoryContext)))return;
        ArrayList<JSONObject> models=new ArrayList<>(activeMessageModels.values());models.sort((a,b)->Integer.compare(a.optInt("id"),b.optInt("id")));
        while(models.size()>300){JSONObject m=models.remove(olderPage?models.size()-1:0);String key=(activeGroupId>0?"g":"c")+m.optInt("id");removeMessageBlock(renderedMessageViews.remove(key));renderedMessageIds.remove(key);activeMessageModels.remove(key);}
        if(olderPage){if(activeMessageScroll instanceof ChatMessageScroll)((ChatMessageScroll)activeMessageScroll).stopFollowing();viewingHistoryContext=true;showNewMessagesButton();}else{oldestHistoryCursor=firstRenderedMessageId();noOlderMessages=false;if(olderMessagesButton!=null)olderMessagesButton.setText("查看更早消息");scrollMessagesToBottom();}
        refreshChatTimeLabels();
    }
    private void refreshChatTimeLabels(){if(activeMessageList==null)return;for(int i=0;i<activeMessageList.getChildCount();i++){View block=activeMessageList.getChildAt(i);if(!(block.getTag() instanceof Integer))continue;JSONObject m=activeMessageModels.get((activeGroupId>0?"g":"c")+block.getTag());View time=block.findViewWithTag("message-time");if(m!=null&&time!=null)time.setVisibility(shouldShowChatTime(m)?View.VISIBLE:View.GONE);}}

    private void playMessageTone() {
        try {
            if (messageTone == null) messageTone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75);
            messageTone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (Exception ignored) { }
    }

    private String voiceClock(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(java.util.Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
    }

    /** 语音气泡：标签 + 进度条，播放中可拖动进度。 */
    private View addVoiceMessage(LinearLayout parent, String url, String label, boolean mine, String avatarText, String avatarUrl) {
        LinearLayout line = row();
        line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
        String duration = label.replaceAll("[^0-9]", "");
        if (duration.isEmpty()) duration = "1";
        final String durationLabel = duration;
        final String idleLabel = "◖))   " + durationLabel + "″";
        final int maxWidth = Math.min(240, Math.max(148, 100 + Integer.parseInt(duration) * 4));

        LinearLayout box = column();
        box.setPadding(dp(14), dp(9), dp(14), dp(9));
        box.setBackground(round(mine ? BLUE : Color.WHITE, 12));
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(maxWidth), -2);
        boxParams.setMargins(mine ? dp(52) : dp(8), dp(5), mine ? 0 : dp(52), dp(5));

        TextView bubble = centeredText(idleLabel, 15, mine ? Color.WHITE : TEXT);
        bubble.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        box.addView(bubble, new LinearLayout.LayoutParams(-1, -2));

        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setPadding(0, 0, 0, 0);
        android.graphics.drawable.ColorDrawable thumb = new android.graphics.drawable.ColorDrawable(mine ? Color.WHITE : BLUE);
        thumb.setBounds(0, 0, dp(12), dp(12));
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(6));
        if (bar.getProgressDrawable() != null) bar.getProgressDrawable().setColorFilter(mine ? Color.WHITE : BLUE, android.graphics.PorterDuff.Mode.SRC_IN);
        bar.setBackgroundColor(Color.TRANSPARENT);
        bar.setVisibility(View.GONE);
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(20)));

        if (!mine) line.addView(avatarWithRemoteImage(avatarText, avatarUrl, "#A8C0FF", "#3F5EFB", 36));
        line.addView(box, boxParams);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));

        boolean[] playerReady = {false};
        boolean[] loading = {false};
        String path = Uri.parse(url).getLastPathSegment();
        String extension = path != null && path.matches(".*\\.[A-Za-z0-9]{2,5}") ? path.substring(path.lastIndexOf('.')) : ".3gp";
        File voiceCache = new File(new File(new File(getFilesDir(), "media-cache"), "voice"), Integer.toHexString((ApiClient.baseUrl(this) + ":" + currentUserId + ":" + url).hashCode()) + extension);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                MediaPlayer player = activeVoicePlayer;
                if (player == null || !playerReady[0]) return;
                try {
                    int total = player.getDuration();
                    if (total > 0) player.seekTo(total * seekBar.getProgress() / 1000);
                } catch (Exception ignored) { }
            }
        });

        box.setOnClickListener(v -> {
            if (url.isEmpty()) return;
            if (loading[0]) return;
            if (activeVoicePlayer != null && activeVoiceBubble == bubble) {
                if (!playerReady[0]) return;
                try {
                    if (activeVoicePlayer.isPlaying()) {
                        activeVoicePlayer.pause();
                        bubble.setText("▶))   " + durationLabel + "″");
                    } else {
                        activeVoicePlayer.start();
                        bubble.setText("Ⅱ))   " + durationLabel + "″");
                    }
                } catch (Exception ignored) { stopActiveVoicePlayback(); }
                return;
            }
            stopActiveVoicePlayback();
            playerReady[0] = false;
            if (voiceCache.isFile() && voiceCache.length() > 0) {
                startVoicePlayback(bubble, bar, voiceCache, durationLabel, playerReady);
                return;
            }
            loading[0] = true;
            activeVoiceBubble = bubble;
            activeVoiceBar = bar;
            activeVoiceLabel = idleLabel;
            bubble.setText("◉ " + t("loading_voice"));
            downloadVoiceFile(url, voiceCache, bubble, bar, durationLabel, playerReady, loading);
        });
        return box;
    }

    private void downloadVoiceFile(String url, File destination, TextView bubble, SeekBar bar, String durationLabel, boolean[] playerReady, boolean[] loading) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            File parent = destination.getParentFile();
            File partial = new File(destination.getAbsolutePath() + ".part");
            try {
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IllegalStateException("voice cache directory creation failed");
                connection = ApiClient.openConnection(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IllegalStateException("voice download failed (HTTP " + status + ")");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                if (partial.length() <= 0) throw new IllegalStateException("voice file is empty");
                if (destination.exists() && !destination.delete()) throw new IllegalStateException("voice cache replacement failed");
                if (!partial.renameTo(destination)) throw new IllegalStateException("voice cache save failed");
                runOnUiThread(() -> {
                    loading[0] = false;
                    if (activeVoiceBubble != bubble) return;
                    startVoicePlayback(bubble, bar, destination, durationLabel, playerReady);
                });
            } catch (Exception exception) {
                partial.delete();
                String message = t("voice_download_failed");
                runOnUiThread(() -> {
                    loading[0] = false;
                    if (activeVoiceBubble == bubble) {
                        activeVoiceBubble = null;
                        activeVoiceBar = null;
                        activeVoiceLabel = "";
                        if (bar != null) bar.setVisibility(View.GONE);
                        bubble.setText("◖))   " + durationLabel + "″");
                        toast(message);
                    }
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "spx-voice-download").start();
    }

    private void startVoicePlayback(TextView bubble, SeekBar bar, File file, String durationLabel, boolean[] playerReady) {
        try {
            MediaPlayer player = new MediaPlayer();
            activeVoicePlayer = player;
            activeVoiceBubble = bubble;
            activeVoiceBar = bar;
            activeVoiceLabel = "◖))   " + durationLabel + "″";
            playerReady[0] = false;
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(prepared -> {
                if (activeVoicePlayer != prepared) return;
                playerReady[0] = true;
                bubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                bubble.setText("Ⅱ))   " + durationLabel + "″");
                if (bar != null) {
                    bar.setProgress(0);
                    bar.setVisibility(View.VISIBLE);
                    startVoiceProgressTicker();
                }
                prepared.start();
            });
            player.setOnCompletionListener(done -> {
                if (activeVoicePlayer == done) {
                    activeVoicePlayer = null;
                    activeVoiceBubble = null;
                    activeVoiceBar = null;
                    activeVoiceLabel = "";
                    stopVoiceProgressTicker();
                    if (bar != null) { bar.setProgress(0); bar.setVisibility(View.GONE); }
                }
                done.release();
                bubble.setText("◖))   " + durationLabel + "″");
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (activeVoicePlayer == failed) {
                    activeVoicePlayer = null;
                    activeVoiceBubble = null;
                    activeVoiceBar = null;
                    activeVoiceLabel = "";
                    stopVoiceProgressTicker();
                    if (bar != null) { bar.setProgress(0); bar.setVisibility(View.GONE); }
                }
                try { failed.reset(); } catch (Exception ignored) { }
                failed.release();
                bubble.setText("◖))   " + durationLabel + "″");
                toast(t("voice_invalid"));
                return true;
            });
            player.prepareAsync();
            bubble.setText("◉ " + t("loading_voice"));
        } catch (Exception exception) {
            stopActiveVoicePlayback();
            bubble.setText("◖))   " + durationLabel + "″");
            toast(t("voice_play_failed"));
        }
    }

    private void startVoiceProgressTicker() {
        stopVoiceProgressTicker();
        voiceProgressTick = new Runnable() {
            @Override public void run() {
                MediaPlayer player = activeVoicePlayer;
                SeekBar bar = activeVoiceBar;
                if (player == null || bar == null) return;
                try {
                    if (player.isPlaying()) {
                        int total = player.getDuration();
                        int position = player.getCurrentPosition();
                        if (total > 0) {
                            if (!bar.isPressed()) bar.setProgress(Math.min(1000, position * 1000 / total));
                            if (activeVoiceBubble != null) activeVoiceBubble.setText("Ⅱ))  " + voiceClock(position / 1000) + " / " + voiceClock(total / 1000));
                        }
                    }
                } catch (Exception ignored) { return; }
                voiceProgressHandler.postDelayed(this, 250);
            }
        };
        voiceProgressHandler.postDelayed(voiceProgressTick, 250);
    }

    private void stopVoiceProgressTicker() {
        if (voiceProgressTick != null) voiceProgressHandler.removeCallbacks(voiceProgressTick);
        voiceProgressTick = null;
    }

    private void stopActiveVoicePlayback() {
        stopVoiceProgressTicker();
        MediaPlayer player = activeVoicePlayer;
        TextView bubble = activeVoiceBubble;
        SeekBar bar = activeVoiceBar;
        String label = activeVoiceLabel;
        activeVoicePlayer = null;
        activeVoiceBubble = null;
        activeVoiceBar = null;
        activeVoiceLabel = "";
        if (bar != null) { bar.setProgress(0); bar.setVisibility(View.GONE); }
        if (bubble != null) {
            bubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            if (!label.isEmpty()) bubble.setText(label);
        }
        if (player == null) return;
        try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) { }
        try { player.reset(); } catch (Exception ignored) { }
        try { player.release(); } catch (Exception ignored) { }
    }

    private void showMessageActions(JSONObject message, View messageView, boolean mine, boolean media) {
        if("red_packet".equals(message.optString("message_type"))){
            new AlertDialog.Builder(this).setItems(new String[]{"查看红包",t("delete")},(d,n)->{if(n==1)hideLocalMessage(message,messageView);else try{redPackets().detail(new JSONObject(message.optString("content")).optString("packet_id"));}catch(Exception e){toast("红包信息不可用");}}).show();return;
        }
        ArrayList<String> actions = new ArrayList<>();
        boolean product="product".equals(message.optString("message_type"));
        if(product){actions.add("转发商品卡");actions.add("复制商品链接");}
        else if(!media)actions.add(t("copy"));
        actions.add(t("reply"));
        if(!product)actions.add("转发");
        actions.add("多选");
        boolean callRecord="call".equals(message.optString("message_origin"));
        final int actionChat = activeChatId, actionOwner = currentUserId;
        final String actionToken = ApiClient.token(this), actionServer = ApiClient.baseUrl(this);
        final int callMedia = ChatCallRecord.media(message.optString("message_origin"), message.optString("message_type", "text"), message.optString("content"), activeGroupId);
        if(callMedia != 0)actions.add("重新拨打");
        if (mine && !callRecord && "text".equals(message.optString("message_type", "text"))) actions.add(t("edit_message"));
        if(mine&&!callRecord)actions.add(t("delete_everyone"));
        actions.add(t("delete"));
        if (actions.isEmpty()) return;
        new AlertDialog.Builder(this).setItems(actions.toArray(new String[0]), (dialog, which) -> {
            String action = actions.get(which);
            if("转发商品卡".equals(action)||"复制商品链接".equals(action)){
                try{JSONObject p=new JSONObject(message.optString("content"));int pid=p.optInt("product_id");if(pid<=0){toast("商品不可用");return;}if("复制商品链接".equals(action))copyText("https://spx001.top/spxchat/product-share.php?id="+pid,"商品链接");else{p.put("id",pid);p.put("spx_price",p.optString("price"));mallShareProduct(p);}}catch(Exception e){toast("商品卡片不可用");}
            }
            else if("重新拨打".equals(action)){
                if(actionOwner == currentUserId && actionToken.equals(ApiClient.token(this)) && actionServer.equals(ApiClient.baseUrl(this)))
                    showCallRecordRedial(actionChat,callMedia);
            }
            else if (t("copy").equals(action)) copyText(message.optString("content", ""), t("message_label"));
            else if (t("reply").equals(action) && activeMessageInput != null) {
                activeReplyMessage=message;updateReplyPreview();saveActiveChatDraft();activeMessageInput.requestFocus();
            } else if("多选".equals(action)){enterSelectionMode(message);
            } else if("转发".equals(action)){chatTools().forwardMessage(message);
            } else if (t("edit_message").equals(action)) editChatMessage(message, messageView);
            else if (t("delete_everyone").equals(action)) deleteChatMessage(message, messageView);
            else if (t("delete").equals(action)) hideLocalMessage(message, messageView);
        }).show();
    }

    private void removeMessageBlock(View messageView) {
        if (messageView == null || activeMessageList == null) return;
        View block = messageView;
        while (block.getParent() instanceof View && block.getParent() != activeMessageList)
            block = (View) block.getParent();
        if (block.getParent() == activeMessageList) activeMessageList.removeView(block);
    }

    private boolean suppressDeleteSync = false;

    private void hideLocalMessage(JSONObject message, View messageView) {
        int conversationId = activeGroupId > 0 ? activeGroupId : activeChatId;
        String scope = activeGroupId > 0 ? "group" : "chat";
        int messageId = message.optInt("id");
        if (conversationId <= 0 || messageId <= 0 || chatCache == null) return;
        chatCache.hide(scope, conversationId, messageId);
        hiddenMessageIds.add(messageId);
        String key = (activeGroupId > 0 ? "g" : "c") + messageId;
        renderedMessageIds.remove(key);
        renderedMessageViews.remove(key);
        activeMessageModels.remove(key);
        removeMessageBlock(messageView);
        refreshChatTimeLabels();
        // 同步到服务端：仅影响自己（换设备/重装后依旧不可见）
        if (suppressDeleteSync) { toast(t("deleted")); return; }
        try {
            JSONObject syncBody = new JSONObject();
            if (activeGroupId > 0) syncBody.put("group_id", conversationId);
            else syncBody.put("chat_id", conversationId);
            syncBody.put("message_ids", new JSONArray().put(messageId));
            ApiClient.post(this, activeGroupId > 0 ? "groups.messages.delete.local" : "messages.delete.local",
                syncBody, new ApiClient.Callback() {
                    public void onSuccess(Object data) { }
                    public void onError(String error) { }
                });
        } catch (Exception ignored) { }
        toast(t("deleted"));
    }

    private void editChatMessage(JSONObject message, View messageView) {
        final int targetChat = activeChatId, targetGroup = activeGroupId;
        final String targetScope = targetGroup > 0 ? "group" : "chat";
        final int targetId = targetGroup > 0 ? targetGroup : targetChat;
        EditText editor = new EditText(this);
        editor.setFilters(new android.text.InputFilter[]{ChatText.FILTER});
        editor.setText(message.optString("content", ""));
        editor.setMinLines(2);
        new AlertDialog.Builder(this).setTitle(t("edit_message")).setView(editor).setNegativeButton(t("cancel"), null).setPositiveButton(t("save"), (dialog, which) -> {
            JSONObject request = new JSONObject();
            try {
                request.put(targetGroup > 0 ? "group_id" : "chat_id", targetId);
                request.put("message_id", message.optInt("id"));
                request.put("content", editor.getText().toString().trim());
            } catch (Exception ignored) { return; }
            ApiClient.post(this, targetGroup > 0 ? "groups.messages.edit" : "messages.edit", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    String updated = editor.getText().toString().trim();
                    try { message.put("content", updated); } catch (Exception ignored) { }
                    if (messageView instanceof TextView) { ((TextView) messageView).setText(updated);productMessageLinks((TextView)messageView,updated); }
                    chatCache.put(targetScope, targetId, message);
                    invalidateChatSnapshot();
                    toast(t("message_edited"));
                }
                @Override public void onError(String error) { toast(error); }
            });
        }).show();
    }

    private void deleteChatMessage(JSONObject message, View messageView) {
        final int targetChat = activeChatId, targetGroup = activeGroupId;
        final String targetScope = targetGroup > 0 ? "group" : "chat";
        final int targetId = targetGroup > 0 ? targetGroup : targetChat;
        new AlertDialog.Builder(this).setMessage(t("delete_message_confirm")).setNegativeButton(t("cancel"), null).setPositiveButton(t("delete"), (dialog, which) -> {
            JSONObject request = new JSONObject();
            try {
                request.put(targetGroup > 0 ? "group_id" : "chat_id", targetId);
                request.put("message_id", message.optInt("id"));
            } catch (Exception ignored) { return; }
            ApiClient.post(this, targetGroup > 0 ? "groups.messages.delete" : "messages.delete", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    String key = (targetGroup > 0 ? "g" : "c") + message.optInt("id");
                    if (activeChatId == targetChat && activeGroupId == targetGroup) {
                        View currentView = renderedMessageViews.remove(key);
                        renderedMessageIds.remove(key);activeMessageModels.remove(key);
                        removeMessageBlock(currentView);
                    }
                    chatCache.remove(targetScope, targetId, message.optInt("id"));
                    invalidateChatSnapshot();
                    toast(t("message_deleted_everyone"));
                }
                @Override public void onError(String error) { toast(error); }
            });
        }).show();
    }

    private interface EmojiAction { void onPick(String value); }

    private void selectMessagesToDelete(JSONObject initial){
        ArrayList<JSONObject> rows=new ArrayList<>(activeMessageModels.values());
        rows.removeIf(m->"system".equals(m.optString("message_origin")));
        if(rows.isEmpty()){toast("没有可删除的记录");return;}
        String[] labels=new String[rows.size()];boolean[] checked=new boolean[rows.size()];
        for(int i=0;i<rows.size();i++){JSONObject m=rows.get(i);String text=chatReplyText(m);if(text.length()>40)text=text.substring(0,40)+"…";labels[i]=m.optString("nickname","")+"："+text;}
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("选择要删除的记录（仅自己可见，最多 200 条）")
            .setMultiChoiceItems(labels,checked,(d,n,value)->{checked[n]=value;})
            .setNegativeButton(t("cancel"),null)
            .setPositiveButton("删除",null)
            .create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            ArrayList<Integer> ids=new ArrayList<>();
            for(int i=0;i<checked.length;i++)if(checked[i])ids.add(rows.get(i).optInt("id"));
            if(ids.isEmpty()){toast("请选择要删除的记录");return;}
            if(ids.size()>200){toast("一次最多删除 200 条");return;}
            final boolean group=activeGroupId>0;final int conv=group?activeGroupId:activeChatId;
            JSONObject body=new JSONObject();
            try{
                if(group)body.put("group_id",conv);else body.put("chat_id",conv);
                body.put("message_ids",new JSONArray(ids));
            }catch(Exception e){return;}
            dialog.dismiss();
            ApiClient.post(this,group?"groups.messages.delete.local":"messages.delete.local",body,new ApiClient.Callback(){
                public void onSuccess(Object data){
                    for(int i=0;i<ids.size();i++){
                        int id=ids.get(i);String key=(group?"g":"c")+id;
                        JSONObject m=activeMessageModels.get(key);
                        if(m!=null)hideLocalMessage(m,renderedMessageViews.get(key));
                    }
                    toast("已删除 "+ids.size()+" 条记录（仅自己）");
                }
                public void onError(String error){toast(error);}
            });
        }));
        dialog.show();
    }

    private void selectMessagesToForward(JSONObject initial){
        ArrayList<JSONObject> rows=new ArrayList<>(activeMessageModels.values());rows.removeIf(m->"system".equals(m.optString("message_origin"))||"red_packet".equals(m.optString("message_type")));rows.sort((a,b)->Integer.compare(a.optInt("id"),b.optInt("id")));
        if(rows.isEmpty())return;String[] labels=new String[rows.size()];boolean[] checked=new boolean[rows.size()];
        for(int i=0;i<rows.size();i++){JSONObject m=rows.get(i);String text=chatReplyText(m);labels[i]=m.optString("nickname","")+"："+(text.length()>80?text.substring(0,80)+"…":text);checked[i]=m.optInt("id")==initial.optInt("id");}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选择消息（最多 20 条）").setMultiChoiceItems(labels,checked,(d,n,value)->{checked[n]=value;if(value){int count=0;for(boolean chosen:checked)if(chosen)count++;if(count>20){((AlertDialog)d).getListView().setItemChecked(n,false);checked[n]=false;toast("一次最多转发 20 条消息");}}}).setNegativeButton(t("cancel"),null).setPositiveButton("下一步",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{JSONArray chosen=new JSONArray();for(int i=0;i<rows.size();i++)if(checked[i])chosen.put(rows.get(i));if(chosen.length()==0){toast("请先选择消息");return;}dialog.dismiss();chatTools().forwardMessages(chosen);}));dialog.show();
    }
    void queueForwardMessages(int chat,int group,JSONArray sources){
        int queued=0;for(int i=0;i<sources.length();i++)if(enqueueForward(chat,group,sources.optJSONObject(i)))queued++;toast("已加入 "+queued+" 条消息，失败可在对应会话重试");
    }
    void queueForward(int chat,int group,JSONObject source){
        if(enqueueForward(chat,group,source))toast("已加入发送队列，失败可在对应会话重试");else toast("无法转发这条消息");
    }
    private boolean enqueueForward(int chat,int group,JSONObject source){
        if(source==null||"red_packet".equals(source.optString("message_type")))return false;
        try{JSONObject body=new JSONObject();body.put("message_type",source.optString("message_type","text")).put("content",source.optString("content","")).put("media_url",source.optString("media_url","")).put("client_message_id",UUID.randomUUID().toString());
            if("product".equals(source.optString("message_type")))body.put("product_id",new JSONObject(source.optString("content")).optInt("product_id"));
            outbox().enqueue(chat,group,body);return true;
        }catch(Exception e){return false;}
    }

    private ChatOutbox outbox() {
        if(chatOutbox==null||!chatOutbox.belongsTo(currentUserId)){
            if(chatOutbox!=null)chatOutbox.stop();
            chatOutbox=new ChatOutbox(this,currentUserId,new ChatOutbox.Listener(){
                public void changed(){renderPendingOutbox();}
                public void sent(JSONObject task,JSONObject sent){
                    try{JSONObject message=new JSONObject(task.toString());java.util.Iterator<String> keys=sent.keys();while(keys.hasNext()){String key=keys.next();message.put(key,sent.get(key));}
                        message.put("sender_id",currentUserId).put("nickname",userName).put("avatar_url",remoteAvatarUrl);
                        final String scope=task.optInt("group_id")>0?"group":"chat";final int target=task.optInt("group_id")>0?task.optInt("group_id"):task.optInt("chat_id");
                        chatCache.execute(()->chatCache.put(scope,target,message));invalidateChatSnapshot();
                        if(chatWindowOpen&&activeChatId==task.optInt("chat_id")&&activeGroupId==task.optInt("group_id")&&activeMessageList!=null){
                            boolean bottom=isMessageScrollAtBottom();removePendingOutboxView(task.optString("client_message_id"));renderChatMessage(message,activeMessageList,activeMessageScroll,activeChatName,activeChatAvatar,activeChatC1,activeChatC2);if(bottom)scrollMessagesToBottom();}
                    }catch(Exception e){toast("消息已发送，请刷新查看");}
                }
            });
        }return chatOutbox;
    }
    private void removePendingOutboxView(String client){View v=pendingOutboxViews.remove(client);if(v!=null&&v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}
    private void renderPendingOutbox(){
        if(viewingHistoryContext||!chatWindowOpen||activeMessageList==null||chatOutbox==null||!chatOutbox.belongsTo(currentUserId))return;
        boolean bottom=isMessageScrollAtBottom(),structureChanged=false;
        Set<String> remaining=new HashSet<>(pendingOutboxViews.keySet());
        JSONArray tasks=chatOutbox.list(activeChatId,activeGroupId);
        for(int i=0;i<tasks.length();i++){
            final JSONObject task=tasks.optJSONObject(i);if(task==null)continue;
            String client=task.optString("client_message_id");remaining.remove(client);
            LinearLayout block=(LinearLayout)pendingOutboxViews.get(client);
            if(block==null){
                block=column();
                if(task.optJSONObject("reply")!=null)addReplyCard(block,task.optJSONObject("reply"),true);
                if ("image".equals(task.optString("message_type")) || "video".equals(task.optString("message_type"))) addPendingMediaPreview(block, task);
                 else if("file".equals(task.optString("message_type"))) addFileMessage(block,"",task.optString("content","文件"),true,userName,remoteAvatarUrl);
                else addMessage(block,t("self_label"),t("self_label"),activeChatC1,activeChatC2,task.optString("content",""),true,remoteAvatarUrl);
                TextView status=bodyText("",12,MUTED);status.setTag("pending-status");
                status.setGravity(Gravity.RIGHT);status.setPadding(dp(12),0,dp(4),dp(10));
                block.addView(status,new LinearLayout.LayoutParams(-1,-2));
                ProgressBar progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progressBar.setTag("pending-progress");progressBar.setMax(100);progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(36,128,99)));progressBar.setVisibility(View.GONE);LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(-1,dp(3));progressParams.setMargins(dp(58),0,dp(8),dp(8));block.addView(progressBar,progressParams);
                activeMessageList.addView(block,new LinearLayout.LayoutParams(-1,-2));
                pendingOutboxViews.put(client,block);structureChanged=true;
            }
            TextView status=(TextView)block.findViewWithTag("pending-status");
            ProgressBar progressBar=(ProgressBar)block.findViewWithTag("pending-progress");
            boolean staging="staging".equals(task.optString("local_state")),uploading="uploading".equals(task.optString("local_state"));
            if(progressBar!=null){progressBar.setVisibility(staging||uploading?View.VISIBLE:View.GONE);progressBar.setIndeterminate(staging);if(uploading)progressBar.setProgress(Math.max(0,Math.min(100,task.optInt("local_progress",0))));}
            boolean failed="failed".equals(task.optString("local_state"));
            int progress=task.optInt("local_progress",0);
            String label=failed?"发送失败 · 点击重试":"staging".equals(task.optString("local_state"))?"正在准备附件…":"sending".equals(task.optString("local_state"))?"发送中…":"uploading".equals(task.optString("local_state"))?"附件上传中"+(progress>0?" · "+progress+"%":"…"):"等待发送…";
            if(!label.contentEquals(status.getText()))status.setText(label);
            status.setTextColor(failed?Color.rgb(202,64,64):MUTED);
            status.setContentDescription(failed?"发送失败，点击查看原因和重试，长按移除":label);
            status.setOnClickListener(null);status.setOnLongClickListener(null);
            status.setClickable(false);status.setLongClickable(false);
            if(failed){
                status.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("消息发送失败").setMessage(task.optString("local_error","网络异常，请重试")).setNegativeButton(t("cancel"),null).setPositiveButton("重试",(d,w)->chatOutbox.retry(client)).show());
                status.setOnLongClickListener(v->{new AlertDialog.Builder(this).setMessage("移除这条未发送的消息？").setNegativeButton(t("cancel"),null).setPositiveButton("移除",(d,w)->chatOutbox.discard(client)).show();return true;});
            }
        }
        for(String client:remaining){removePendingOutboxView(client);structureChanged=true;}
        if(bottom&&structureChanged)scrollMessagesToBottom();
    }
    private void addPendingMediaPreview(LinearLayout block, JSONObject task) {
        boolean video="video".equals(task.optString("message_type"));
        FrameLayout frame=new FrameLayout(this);frame.setBackground(round(Color.rgb(238,242,248),12));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(210),dp(150));size.gravity=Gravity.RIGHT;size.setMargins(dp(52),dp(6),0,dp(2));block.addView(frame,size);
        String path=task.optString("local_file","");
        if(!path.isEmpty()&&video)loadPendingVideo(Uri.fromFile(new File(path)),frame);
        else if(!path.isEmpty())loadPendingImage(Uri.fromFile(new File(path)),frame);
        else if(!task.optString("local_uri","").isEmpty()){
            Uri uri=Uri.parse(task.optString("local_uri"));
            if(video)loadPendingVideo(uri,frame);
            else loadPendingImage(uri,frame);
        } else {TextView text=centeredText(video?"视频":"图片",14,MUTED);frame.addView(text,new FrameLayout.LayoutParams(-1,-1));}
    }
    private void loadPendingImage(Uri uri, FrameLayout frame) {
        ChatMediaWork.execute(()->{Bitmap bitmap=null;try{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,o);}o.inSampleSize=1;while(o.outWidth/o.inSampleSize>dp(420)||o.outHeight/o.inSampleSize>dp(300))o.inSampleSize*=2;o.inJustDecodeBounds=false;try(InputStream in=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,o);}}catch(Exception ignored){}Bitmap decoded=bitmap;frame.post(()->{if(decoded!=null&&frame.isAttachedToWindow()){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(decoded);frame.addView(image,0,new FrameLayout.LayoutParams(-1,-1));}});});
    }
    private void loadPendingVideo(Uri uri,FrameLayout frame){
        TextView play=centeredText("▶",26,Color.WHITE);play.setBackground(round(Color.argb(130,15,23,32),24));FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER);frame.addView(play,pp);
        ChatMediaWork.execute(()->{
            Bitmap bitmap=null;String duration="";MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try{retriever.setDataSource(this,uri);String raw=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);long seconds=raw==null?0:Long.parseLong(raw)/1000;duration=String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
                if(android.os.Build.VERSION.SDK_INT>=27)bitmap=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,360);else bitmap=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if(bitmap!=null&&Math.max(bitmap.getWidth(),bitmap.getHeight())>640){float scale=640f/Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*scale)),Math.max(1,(int)(bitmap.getHeight()*scale)),true);if(scaled!=bitmap)bitmap.recycle();bitmap=scaled;}
            }catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
            final Bitmap thumb=bitmap;final String time=duration;
            frame.post(()->{if(!frame.isAttachedToWindow()){if(thumb!=null)thumb.recycle();return;}if(thumb!=null){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(thumb);frame.addView(image,0,new FrameLayout.LayoutParams(-1,-1));}if(!time.isEmpty()){TextView label=bodyText(time,11,Color.WHITE);label.setPadding(dp(5),dp(2),dp(5),dp(2));label.setBackground(round(Color.argb(140,0,0,0),6));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);lp.setMargins(0,0,dp(6),dp(6));frame.addView(label,lp);}});
        });
    }
    private void sendChatText(String text,EditText input,TextView send,LinearLayout list,ScrollView scroll,String name,String avatarText,String c1,String c2){
        if(text.isEmpty()||(activeChatId<=0&&activeGroupId<=0))return;if(ChatText.count(text)>ChatText.LIMIT){toast("消息最多 10000 个字");return;}
        try{JSONObject body=new JSONObject();body.put("content",text).put("message_type","text").put("client_message_id",UUID.randomUUID().toString());
            if(activeGroupId>0&&!activeMentionUserIds.isEmpty()){JSONArray valid=new JSONArray();for(Integer id:activeMentionUserIds){String memberName=activeMentionNames.get(id);if(memberName!=null&&text.contains("@"+memberName))valid.put(id);}body.put("mention_user_ids",valid);}
            if(activeReplyMessage!=null){body.put("reply_to_message_id",activeReplyMessage.optInt("id"));body.put("reply",new JSONObject().put("id",activeReplyMessage.optInt("id")).put("nickname",activeReplyMessage.optString("nickname","")).put("preview",chatReplyText(activeReplyMessage)).put("available",true));}
            outbox().enqueue(activeChatId,activeGroupId,body);activeMentionUserIds.clear();activeMentionNames.clear();activeReplyMessage=null;updateReplyPreview();input.setText("");saveActiveChatDraft();scrollMessagesToBottom();
        }catch(Exception e){toast("消息未能保存，请重试");}
    }
    private long chatTimestamp(JSONObject m){try{java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.ROOT);f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));return f.parse(m.optString("created_at","")).getTime();}catch(Exception e){return 0;}}
    private boolean shouldShowChatTime(JSONObject message){int id=message.optInt("id"),previous=0;JSONObject before=null;for(JSONObject item:activeMessageModels.values()){int n=item.optInt("id");if(n<id&&n>previous){previous=n;before=item;}}if(before==null)return true;long a=chatTimestamp(before),b=chatTimestamp(message);return a==0||b==0||Math.abs(b-a)>=300000L||!before.optString("created_at").substring(0,Math.min(10,before.optString("created_at").length())).equals(message.optString("created_at").substring(0,Math.min(10,message.optString("created_at").length())));}

    private ImageView chatControl(String kind,String label){ImageView icon=new ImageView(this);icon.setImageDrawable(new ChatControlIcon(kind,TEXT));icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setPadding(dp(9),dp(9),dp(9),dp(9));icon.setContentDescription(label);return icon;}
    private String chatReplyText(JSONObject message){String type=message.optString("message_type","text");return "red_packet".equals(type)?"[SPX001 红包]":"image".equals(type)?"[图片]":"video".equals(type)?"[视频]":"voice".equals(type)?"[语音]":"file".equals(type)?"[文件] "+fileMessageName(message.optString("content")):"product".equals(type)?productPreview(message.optString("content")):message.optString("content","");}
    private void updateReplyPreview(){if(activeReplyPreview==null)return;activeReplyPreview.setVisibility(activeReplyMessage==null?View.GONE:View.VISIBLE);if(activeReplyMessage!=null){String text=chatReplyText(activeReplyMessage);activeReplyPreview.setMaxLines(2);activeReplyPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);activeReplyPreview.setText("回复 "+activeReplyMessage.optString("nickname","")+"："+text+"   ×");activeReplyPreview.setContentDescription("正在引用消息，点击取消引用");}}
    private void addReplyCard(LinearLayout parent,JSONObject reply,boolean mine){LinearLayout line=row();line.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);TextView card=bodyText(reply.optString("nickname","")+"："+reply.optString("preview","原消息已删除"),12,MUTED);card.setMaxLines(2);card.setEllipsize(android.text.TextUtils.TruncateAt.END);card.setMaxWidth(Math.min(dp(270),getResources().getDisplayMetrics().widthPixels-dp(108)));card.setPadding(dp(12),dp(9),dp(12),dp(9));card.setBackground(round(Color.rgb(231,236,244),8));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(mine?dp(48):dp(46),dp(4),0,0);line.addView(card,lp);parent.addView(line,new LinearLayout.LayoutParams(-1,-2));card.setOnClickListener(v->{if(reply.optBoolean("available",true))locateChatMessage(reply.optInt("id"));else toast("原消息已删除");});}

    private String chatDraftKey() {return "draft_v2:"+ApiClient.baseUrl(this)+":"+currentUserId+":"+(activeGroupId>0?"g:"+activeGroupId:"c:"+activeChatId);}
    private void saveActiveChatDraft() {
        if(!chatWindowOpen||activeMessageInput==null||currentUserId<=0||(activeChatId<=0&&activeGroupId<=0))return;
        String text=activeMessageInput.getText().toString();preferences.edit().putLong("chat_draft_revision",System.currentTimeMillis()).apply();JSONObject draft=new JSONObject();try{JSONObject names=new JSONObject();for(Map.Entry<Integer,String> entry:activeMentionNames.entrySet())names.put(String.valueOf(entry.getKey()),entry.getValue());draft.put("text",text).put("mentions",new JSONArray(activeMentionUserIds)).put("mention_names",names);if(activeReplyMessage!=null)draft.put("reply",activeReplyMessage);}catch(Exception ignored){}
        if(text.isEmpty()&&activeReplyMessage==null)preferences.edit().remove(chatDraftKey()).apply();else preferences.edit().putString(chatDraftKey(),draft.toString()).apply();
    }
    private void restoreActiveChatDraft(EditText input) {try{JSONObject draft=new JSONObject(preferences.getString(chatDraftKey(),"{}"));input.setText(draft.optString("text",""));input.setSelection(input.length());activeReplyMessage=draft.optJSONObject("reply");updateReplyPreview();JSONObject names=draft.optJSONObject("mention_names");JSONArray ids=draft.optJSONArray("mentions");if(ids!=null)for(int i=0;i<ids.length();i++){int id=ids.optInt(i);if(id>0||id==-1){activeMentionUserIds.add(id);if(names!=null&&!names.optString(String.valueOf(id)).isEmpty())activeMentionNames.put(id,names.optString(String.valueOf(id)));}}}catch(Exception ignored){}}
    private void hideChatKeyboard(View input) {android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(input.getWindowToken(),0);}

    private void showEmojiPanel(LinearLayout panel, EmojiAction action) {
        panel.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = column();
        String[] emojis = {"😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😍","🥰","😘","😗","😙","😚","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","🤨","😐","😑","😶","🙄","😏","😣","😥","😮","🤐","😯","😪","😫","🥱","😴","😌","😛","😜","😓","😔","😕","🙃","🫠","😲","☹️","🙁","😖","😞","😟","😤","😢","😭","😦","😧","😨","😩","🤯","😬","😰","😱","🥵","🥶","😳","🤪","😵","😡","😠","🤬","😷","🤒","🤕","🤢","🤮","🤧","🥳","😎","🤓","🧐","😕","😟","👍","👎","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","👇","☝️","✋","🤚","🖐️","🖖","👋","🤏","👏","🙌","👐","🤲","🙏","💪","❤️","🧡","💛","💚","💙","💜","🖤","🤍","💔","💕","💞","💓","💗","💖","💘","💝","💟","❣️","💌","💤","🎉","🎊","✨","🔥","🌹","🌷","🌞","🌈","☀️","⭐","🌟","☕","🍰","🍻","⚽","🎁"};
        for (int start = 0; start < emojis.length; start += 8) {
            LinearLayout row = row();
            for (int i = start; i < Math.min(start + 8, emojis.length); i++) {
                final String value = emojis[i];
                TextView item = centeredText(value, 24, TEXT);
                item.setOnClickListener(v -> action.onPick(value));
                row.addView(item, new LinearLayout.LayoutParams(0, dp(42), 1));
            }
            rows.addView(row, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(210)));
    }

    private void showChatMorePanel(LinearLayout panel) {
        panel.removeAllViews();
        android.widget.GridLayout choices = new android.widget.GridLayout(this);
        choices.setColumnCount(4);
        choices.setUseDefaultMargins(false);
        addChatMoreAction(choices, panel, t("gallery"), "gallery", this::chooseChatMedia);
        addChatMoreAction(choices, panel, t("take_photo"), "camera", () -> {
            panel.setVisibility(View.GONE);
            if (sendingMessage) return;
            sendingMessage = true;
            pendingMediaOwnerId=currentUserId;
            pendingMediaChatId = activeChatId;
            pendingMediaGroupId = activeGroupId;
            pendingMediaClientId = UUID.randomUUID().toString();
            requestCamera(CAMERA_CHAT_PHOTO);
        });
        if(activeChatId>0&&activeGroupId==0){
            addChatMoreAction(choices,panel,"语音通话","phone",()->{stopActiveVoicePlayback();ChatCall call=calls();if(call!=null)call.start(activeChatId,false);});
            addChatMoreAction(choices,panel,"视频通话","video",()->{stopActiveVoicePlayback();ChatCall call=calls();if(call!=null)call.start(activeChatId,true);});
        }
        addChatMoreAction(choices,panel,"红包","redpacket",()->redPackets().compose(activeChatId,activeGroupId));
        addChatMoreAction(choices,panel,"文件","file",this::chooseChatFile);
        addChatMoreAction(choices,panel,"红包记录","history",()->redPackets().history());
        addChatMoreAction(choices,panel,"更多","settings",()->new AlertDialog.Builder(this)
            .setTitle("聊天工具").setItems(new String[]{"定时发送","自动回复"},(dialog,index)->{
                if(index==0)chatTools().schedule(activeChatId,activeGroupId,activeMessageInput==null?"":activeMessageInput.getText().toString());
                else chatTools().rules(activeGroupId);
            }).setNegativeButton("取消",null).show());
        panel.addView(choices,new LinearLayout.LayoutParams(-1,-2));
    }

    private void addChatMoreAction(android.widget.GridLayout parent, LinearLayout panel, String label, String icon, Runnable action) {
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        View image;
        if("phone".equals(icon)||"video".equals(icon)||"file".equals(icon)){
            ImageView callIcon=new ImageView(this);callIcon.setImageDrawable(new ChatControlIcon(icon,Color.rgb(75,87,104)));callIcon.setPadding(dp(8),dp(8),dp(8),dp(8));image=callIcon;
        }else image=new SimpleIconView(this,icon,true);
        image.setBackground(MallUi.surface(this,Color.WHITE,14,true));
        item.addView(image, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView text = centeredText(label, 12, Color.rgb(75, 87, 104));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.addView(text, new LinearLayout.LayoutParams(-1, dp(24)));
        item.setOnClickListener(v -> { panel.setVisibility(View.GONE); action.run(); });
        item.setContentDescription(label);
        int position=parent.getChildCount();
        android.widget.GridLayout.LayoutParams cell=new android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(position/4),android.widget.GridLayout.spec(position%4,1f));
        cell.width=0;cell.height=dp(90);cell.setGravity(Gravity.FILL_HORIZONTAL);
        parent.addView(item,cell);
    }

    private void chooseChatMedia() {
        new AlertDialog.Builder(this).setTitle("从相册选择").setItems(new String[]{"照片","视频"},(d,n)->chooseChatMedia(n==0?"image/*":"video/*")).show();
    }
    private void chooseChatMedia(String mime) {
        if(sendingMessage)return;
        pendingMediaOwnerId=currentUserId;pendingMediaChatId=activeChatId;pendingMediaGroupId=activeGroupId;pendingMediaClientId=UUID.randomUUID().toString();
        sendingMessage=ChatGallery.openMultiple(this,mime,2001);
    }
    private void chooseChatFile(){
        if(sendingMessage||(activeChatId<=0&&activeGroupId<=0))return;
        pendingMediaOwnerId=currentUserId;pendingMediaChatId=activeChatId;pendingMediaGroupId=activeGroupId;
        sendingMessage=ChatGallery.openFiles(this,2003);
    }

    private FrameLayout addMediaMessage(LinearLayout parent, String url, String type, boolean mine, String sender, String avatarText, String c1, String c2, String avatarUrl) {
        LinearLayout line = row();
        line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
        if (!mine) line.addView(avatarWithRemoteImage(avatarText, avatarUrl, c1, c2, 36));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(210), dp(160));
        params.setMargins(mine ? dp(52) : dp(10), dp(6), mine ? 0 : dp(52), dp(6));
        FrameLayout preview = new FrameLayout(this);
        preview.setBackground(round(Color.WHITE, 12));
        TextView placeholder = centeredText(type.equals("video") ? t("preparing_video") : t("loading_image"), 13, MUTED);
        placeholder.setGravity(Gravity.CENTER);
        preview.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER);
        progressParams.bottomMargin = dp(28);
        preview.addView(progress, progressParams);
        line.addView(preview, params);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        if (url.isEmpty()) return preview;
        String path = Uri.parse(url).getLastPathSegment();
        String extension = path != null && path.matches(".*\\.[A-Za-z0-9]{2,5}") ? path.substring(path.lastIndexOf('.')) : ("video".equals(type) ? ".mp4" : ".jpg");
        File cache = chatMediaCacheFile(url, extension);
        if("video".equals(type)){
            progress.setVisibility(View.GONE);placeholder.setText("▶  点击加载视频");
            preview.setContentDescription("视频，点击加载并播放");
            // Playback must be available before an optional cached poster exists.
            final ApiClient.SessionStamp owner = new ApiClient.SessionStamp(this);
            preview.setOnClickListener(v -> {
                if (!owner.valid(this)) return;
                openChatMediaViewer(url, type);
                if (chatMediaViewer != null) chatMediaViewer.whenDismissed(() -> {
                    if (owner.valid(this) && preview.isAttachedToWindow() && cache.isFile() && cache.length() > 0)
                        showCachedMedia(preview, placeholder, cache, type, url);
                });
            });
            if(cache.isFile()&&cache.length()>0)ChatMediaWork.whenVisible(preview,()->showCachedMedia(preview,placeholder,cache,type,url));
            return preview;
        }
        final String mediaToken=ApiClient.token(this),mediaServer=ApiClient.baseUrl(this);
        final java.util.concurrent.atomic.AtomicReference<HttpURLConnection> transfer=new java.util.concurrent.atomic.AtomicReference<>();
        preview.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){HttpURLConnection c=transfer.getAndSet(null);if(c!=null)new Thread(c::disconnect,"spx-image-cancel").start();}
        });
        final Runnable[] loadMedia={null};
        loadMedia[0]=() -> {
          progress.setVisibility(View.VISIBLE);placeholder.setText(t("downloading"));
          ChatMediaWork.execute(url, () -> {
            if (!preview.isAttachedToWindow()||!mediaToken.equals(ApiClient.token(this))||!mediaServer.equals(ApiClient.baseUrl(this))) return;
            if (cache.isFile() && cache.length() > 0) {
                preview.post(() -> { progress.setVisibility(View.GONE); showCachedMedia(preview, placeholder, cache, type, url); });
                return;
            }
            File directory = cache.getParentFile();
            if (directory != null && !directory.exists()) directory.mkdirs();
            HttpURLConnection connection = null;
            File temporary = new File(cache.getAbsolutePath() + ".part");
            try {
                connection = ApiClient.openConnection(this, url);transfer.set(connection);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) throw new IllegalStateException("HTTP " + responseCode);
                String contentType = connection.getContentType();
                if (contentType != null && !contentType.startsWith("video/") && !contentType.startsWith("image/") && !contentType.startsWith("application/octet-stream"))
                    throw new IllegalStateException("invalid media response type");
                long totalBytes = connection.getContentLengthLong();
                if(totalBytes>1024L*1024*1024||(totalBytes>0&&directory.getUsableSpace()<totalBytes+8L*1024*1024))throw new java.io.IOException("存储空间不足或附件过大");
                long downloaded = 0;
                long lastUpdate = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if(!preview.isAttachedToWindow()||!mediaToken.equals(ApiClient.token(this))||!mediaServer.equals(ApiClient.baseUrl(this)))throw new java.io.IOException("媒体加载已取消");
                        downloaded += count;if(downloaded>1024L*1024*1024)throw new java.io.IOException("附件超过 1 GB");
                        output.write(buffer, 0, count);
                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 120) {
                            int percent = totalBytes > 0 ? (int) (downloaded * 100 / totalBytes) : -1;
                            String label = percent >= 0 ? t("download_percent", Math.min(100, percent)) : t("downloading");
                            runOnUiThread(() -> placeholder.setText(label));
                            lastUpdate = now;
                        }
                    }
                }
                if(downloaded<=0||(totalBytes>=0&&downloaded!=totalBytes))throw new java.io.IOException("下载不完整");
                if (!temporary.renameTo(cache)) throw new IllegalStateException("cache file save failed");
                runOnUiThread(() -> progress.setVisibility(View.GONE));
                runOnUiThread(() -> showCachedMedia(preview, placeholder, cache, type, url));
            } catch (Exception ignored) {
                temporary.delete();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    placeholder.setText("加载失败，点击重试");
                    preview.setOnClickListener(v->loadMedia[0].run());
                });
            } finally {
                transfer.compareAndSet(connection,null);if (connection != null) connection.disconnect();
            }
          });
        };
        ChatMediaWork.whenVisible(preview,loadMedia[0]);
        return preview;
    }

    private File chatMediaCacheFile(String url,String extension){
        return chatAttachmentCacheFile(url,".mp4".equals(extension)?"video":"image");
    }
    private File chatAttachmentCacheFile(String url,String type){return ChatOutbox.mediaCacheFile(this,ApiClient.baseUrl(this)+":"+currentUserId,url,type);}

    private void showCachedMedia(FrameLayout preview, TextView placeholder, File file, String type, String url) {
        if (!preview.isAttachedToWindow()) return;
        if ("image".equals(type)) {
            ChatMediaWork.execute(()->{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
                Bitmap bitmap=decodeBitmap(file,dp(440),dp(640));final int iw=bounds.outWidth,ih=bounds.outHeight;
                preview.post(()->{if(!preview.isAttachedToWindow())return;if(bitmap==null){placeholder.setText(t("image_load_failed"));return;}
                    int maxW=Math.min(dp(220),getResources().getDisplayMetrics().widthPixels-dp(110)),maxH=dp(300);float scale=Math.min(maxW/(float)Math.max(1,iw),maxH/(float)Math.max(1,ih));
                    ViewGroup.LayoutParams size=preview.getLayoutParams();size.width=Math.max(dp(48),(int)(iw*scale));size.height=Math.max(dp(48),(int)(ih*scale));preview.setLayoutParams(size);
                    ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(bitmap);image.setContentDescription("图片，点击查看完整大图");preview.removeAllViews();preview.addView(image,new FrameLayout.LayoutParams(-1,-1));
                });
            });
        } else if ("video".equals(type)) {
            placeholder.setText(t("reading_video_frame"));
            ChatMediaWork.execute(() -> {
                Bitmap frame = null;
                File thumbFile=new File(file.getAbsolutePath()+".thumb.jpg");
                if(thumbFile.isFile())frame=decodeBitmap(thumbFile,dp(420),dp(300));
                String duration="";File durationFile=new File(file.getAbsolutePath()+".duration");
                if(durationFile.isFile())try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(durationFile),StandardCharsets.UTF_8))){String cached=reader.readLine();if(cached!=null&&cached.matches("[0-9]{1,7}:[0-5][0-9]"))duration=cached;}catch(Exception ignored){}
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    if(frame==null){frame=retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(frame!=null){int max=640;float scale=Math.min(1f,max/(float)Math.max(frame.getWidth(),frame.getHeight()));if(scale<1f){Bitmap scaled=Bitmap.createScaledBitmap(frame,Math.max(1,(int)(frame.getWidth()*scale)),Math.max(1,(int)(frame.getHeight()*scale)),true);if(scaled!=frame)frame.recycle();frame=scaled;}try(FileOutputStream out=new FileOutputStream(thumbFile)){frame.compress(Bitmap.CompressFormat.JPEG,82,out);}}}
                    if(duration.isEmpty()){String raw=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);long seconds=raw==null?0:Long.parseLong(raw)/1000;if(seconds>0){duration=String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);try(FileOutputStream out=new FileOutputStream(durationFile)){out.write(duration.getBytes(StandardCharsets.UTF_8));}}}
                } catch (Exception ignored) { }
                finally { try { retriever.release(); } catch (Exception ignored) { } }
                Bitmap firstFrame = frame;
                String videoDuration=duration;
                preview.post(() -> {
                    if (!preview.isAttachedToWindow()) return;
                    preview.removeAllViews();
                    if (firstFrame != null) {
                        ImageView image = new ImageView(this);
                        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        image.setImageBitmap(firstFrame);
                        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
                        TextView play = centeredText("▶", 28, Color.WHITE);
                        play.setBackground(round(Color.argb(150, 20, 20, 20), 30));
                        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER);
                        preview.addView(play, playParams);
                        if(!videoDuration.isEmpty()){TextView time=bodyText(videoDuration,11,Color.WHITE);time.setPadding(dp(6),dp(3),dp(6),dp(3));time.setBackground(round(Color.argb(170,0,0,0),8));FrameLayout.LayoutParams timeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);timeParams.setMargins(0,0,dp(8),dp(7));preview.addView(time,timeParams);}
                    } else {
                        TextView failed = centeredText("▶ " + t("tap_to_play"), 14, MUTED);
                        preview.addView(failed, new FrameLayout.LayoutParams(-1, -1));
                    }
                });
            });
        }
        preview.setOnClickListener(v -> openChatMediaViewer(url,type));
    }

    private ChatMediaViewer chatMediaViewer;
    private void openChatMediaViewer(String url,String type){
        ArrayList<JSONObject> messages=new ArrayList<>(activeMessageModels.values());
        messages.sort((a,b)->Integer.compare(a.optInt("id"),b.optInt("id")));
        ArrayList<JSONObject> media=new ArrayList<>();int selected=-1;
        for(JSONObject message:messages){
            String kind=message.optString("message_type"),source=message.optString("media_url");
            if((!"image".equals(kind)&&!"video".equals(kind))||source.isEmpty())continue;
            if(source.equals(url)&&kind.equals(type)&&selected<0)selected=media.size();
            media.add(message);
        }
        if(selected<0)try{
            JSONObject source=new JSONObject().put("message_type",type).put("media_url",url)
                    .put("content","video".equals(type)?"[视频]":"[图片]");
            selected=media.size();media.add(source);
        }catch(Exception e){toast("媒体暂时无法打开");return;}
        stopActiveVoicePlayback();
        if(chatMediaViewer!=null)chatMediaViewer.dismiss();
        chatMediaViewer=ChatMediaViewer.show(this,media,selected,ApiClient.baseUrl(this)+":"+currentUserId,
                source->chatTools().forwardMessage(source));
    }

    private Bitmap decodeBitmap(File file, int maxWidth, int maxHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void sendUploadedMedia(JSONObject upload) {
        if (upload == null) { sendingMessage = false; removePendingMediaBubble(); return; }
        String url = upload.optString("url", "");
        String type = upload.optString("message_type", "image");
        int sendChatId = pendingMediaChatId;
        int sendGroupId = pendingMediaGroupId;
        if (url.isEmpty() || (sendChatId <= 0 && sendGroupId <= 0)) { sendingMessage = false; removePendingMediaBubble(); return; }
        JSONObject body = new JSONObject();
        try {
            body.put(sendGroupId > 0 ? "group_id" : "chat_id", sendGroupId > 0 ? sendGroupId : sendChatId);
            body.put("content", "voice".equals(type) ? t("ui_voice_duration", pendingVoiceDurationSeconds) : (type.equals("video") ? t("ui_video_placeholder") : t("ui_image_placeholder")));
            body.put("message_type", type);
            body.put("media_url", url);
            body.put("client_message_id", pendingMediaClientId);
        } catch (Exception ignored) { return; }
        ApiClient.post(this, sendGroupId > 0 ? "groups.messages.send" : "messages.send", body, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                sendingMessage = false;
                invalidateChatSnapshot();
                removePendingMediaBubble();
                 if (activeChatId == sendChatId && activeGroupId == sendGroupId && activeMessageList != null && activeMessageScroll != null && data instanceof JSONObject) {
                     ScrollView messageScroll = activeMessageScroll;
                     JSONObject message = new JSONObject();
                    try {
                        message.put("id", ((JSONObject) data).optInt("id"));
                        message.put("sender_id", currentUserId);
                        message.put("nickname", userName);
                        message.put("avatar_url", remoteAvatarUrl);
                        message.put("content", "voice".equals(type) ? t("ui_voice_duration", pendingVoiceDurationSeconds) : (type.equals("video") ? t("ui_video_placeholder") : t("ui_image_placeholder")));
                        message.put("message_type", type);
                         message.put("media_url", url);
                         message.put("client_message_id", pendingMediaClientId);
                         renderChatMessage(message, activeMessageList, activeMessageScroll, activeChatName, activeChatAvatar, activeChatC1, activeChatC2);
                         messageScroll.post(() -> { if (activeMessageScroll == messageScroll && messageScroll.getChildCount() > 0) messageScroll.scrollTo(0, messageScroll.getChildAt(0).getHeight()); });
                     } catch (Exception ignored) { }
                }
            }
            @Override public void onError(String message) { sendingMessage = false; removePendingMediaBubble(); toast(message); }
        });
    }

    private TextView addMessage(LinearLayout parent, String name, String avatarText, String c1, String c2, String message, boolean mine) {
        return addMessage(parent, name, avatarText, c1, c2, message, mine, "");
    }

    private TextView addMessage(LinearLayout parent, String name, String avatarText, String c1, String c2, String message, boolean mine, String avatarUrl) {
        LinearLayout line = row();
        line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
        if (!mine) {
            View messageAvatar = avatarWithRemoteImage(avatarText, avatarUrl, c1, c2, 36);
            messageAvatar.setOnClickListener(v -> {
                if (activePeerUserId > 0) showContactDetail(activePeerUserId, name, "", avatarUrl, true);
                else toast(t("conversation_profile_missing"));
            });
            line.addView(messageAvatar);
        }
        TextView bubble = bodyText(message,16,mine?Color.WHITE:TEXT);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setMaxWidth(Math.min(dp(295),getResources().getDisplayMetrics().widthPixels-dp(108)));
        bubble.setBackground(mine?round(BLUE,12):stroke(Color.WHITE,Color.rgb(234,238,246),12,1));
        bubble.setLineSpacing(dp(4),1);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(-2, -2);
        bubbleParams.setMargins(mine ? dp(52) : dp(10), dp(6), mine ? 0 : dp(52), dp(6));
        line.addView(bubble, bubbleParams);
        if (mine) line.setGravity(Gravity.RIGHT);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return bubble;
    }

    private void applyChatBackground(ScrollView messages) {
        if (chatBackgroundPreset > 0) {
            int[] colors;
            if (chatBackgroundPreset == 1) {
                colors = new int[]{Color.rgb(218, 229, 232), Color.rgb(196, 217, 221)};
            } else if (chatBackgroundPreset == 2) {
                colors = new int[]{Color.rgb(226, 241, 248), Color.rgb(205, 229, 241)};
            } else {
                colors = new int[]{Color.rgb(239, 234, 226), Color.rgb(222, 215, 205)};
            }
            messages.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors));
            return;
        }
        if (chatBackgroundUri == null) {
            messages.setBackgroundColor(BG);
            return;
        }
        messages.setBackgroundColor(BG);
        final Uri requested=chatBackgroundUri;final int preset=chatBackgroundPreset;
        final int width=Math.max(1,getResources().getDisplayMetrics().widthPixels),height=Math.max(1,getResources().getDisplayMetrics().heightPixels);
        ChatMediaWork.execute(()->{
            try{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream in=getContentResolver().openInputStream(requested)){BitmapFactory.decodeStream(in,null,bounds);}
                if(bounds.outWidth<=0||bounds.outHeight<=0)return;
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(bounds.outWidth/options.inSampleSize>width*2||bounds.outHeight/options.inSampleSize>height*2)options.inSampleSize*=2;
                final Bitmap bitmap;try(InputStream in=getContentResolver().openInputStream(requested)){bitmap=BitmapFactory.decodeStream(in,null,options);}
                if(bitmap==null)return;
                messages.post(()->{
                    if(isFinishing()||isDestroyed()||activeMessageScroll!=messages||chatBackgroundPreset!=preset||!requested.equals(chatBackgroundUri)){bitmap.recycle();return;}
                    android.graphics.drawable.BitmapDrawable drawable=new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);
                    messages.setBackground(drawable);
                });
            }catch(Exception|OutOfMemoryError ignored){}
        });
    }

    private void closeChatWindow() {
        restoreChatListPosition = !openedChatListSnapshot.isEmpty()
                && openedChatListSnapshot.equals(chatListSortSignature(readChatSnapshot()));
        closeChatSocket();
        activeChatId = 0;
        activePeerUserId = 0;
        activeGroupId = 0;
        chatWindowOpen = false;
        if (restorePreviousPage()) return;
        setBottomNavVisible(true);
        renderTab(activeTab);
    }

    private void openAskWindow() {
        askWindowOpen = true;
        setBottomNavVisible(false);
        content.removeAllViews();
        LinearLayout root = column();
        root.setBackgroundColor(BG);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.setBackgroundColor(Color.WHITE);
        TextView back = centeredText("‹", 34, TEXT);
        back.setOnClickListener(v -> closeAskWindow());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView title = bodyText(t("ask_title"), 19, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(bodyText("?", 22, BLUE), new LinearLayout.LayoutParams(dp(42), dp(48)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(dp(16), dp(18), dp(16), dp(18));
        TextView intro = bodyText(t("ask_intro"), 14, MUTED);
        intro.setPadding(dp(4), dp(4), dp(4), dp(16));
        list.addView(intro);
        ApiClient.get(this, "faq", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) return;
                JSONArray faq = (JSONArray) data;
                for (int i = 0; i < faq.length(); i++) {
                    JSONObject item = faq.optJSONObject(i);
                    if (item != null) addQuestion(list, item.optString("question"), item.optString("answer"), item.optString("category"));
                }
            }
            @Override public void onError(String message) { toast(message); }
        });
        TextView askTitle = bodyText(t("ask_no_answer"), 16, TEXT);
        askTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        list.addView(askTitle);
        EditText question = new EditText(this);
        question.setHint(t("question_input"));
        question.setTextSize(15);
        question.setPadding(dp(14), dp(10), dp(14), dp(10));
        question.setBackground(round(Color.WHITE, 14));
        list.addView(question, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView submit = centeredText(t("submit_question"), 15, Color.WHITE);
        submit.setTypeface(null, android.graphics.Typeface.BOLD);
        submit.setBackground(round(BLUE, 14));
        submit.setOnClickListener(v -> {
            String text = question.getText().toString().trim();
            if (text.isEmpty()) {
                toast(t("question_required"));
            } else {
                JSONObject body = new JSONObject();
                try { body.put("question", text); } catch (Exception ignored) { return; }
                ApiClient.post(this, "questions.create", body, new ApiClient.Callback() {
                    @Override public void onSuccess(Object result) {
                        question.setText("");
                        toast(t("question_submitted"));
                        addQuestion(list, text, t("question_status", result instanceof JSONObject ? ((JSONObject) result).optString("status", "pending") : "pending"), t("my_question"));
                        scroll.post(() -> { if (activeMessageScroll == scroll && scroll.getChildCount() > 0) scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); });
                    }
                    @Override public void onError(String message) { toast(message); }
                });
            }
        });
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(-1, dp(46));
        submitParams.setMargins(0, dp(12), 0, dp(20));
        list.addView(submit, submitParams);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addQuestion(LinearLayout parent, String question, String answer, String tag) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(Color.WHITE, 16));
        TextView tagView = bodyText(tag, 11, BLUE);
        tagView.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(tagView);
        TextView q = bodyText("Q  " + question, 15, TEXT);
        q.setTypeface(null, android.graphics.Typeface.BOLD);
        q.setPadding(0, dp(7), 0, dp(4));
        card.addView(q);
        card.addView(bodyText("A  " + answer, 14, Color.rgb(91, 104, 124)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(12));
        parent.addView(card, params);
    }

    private void closeAskWindow() {
        askWindowOpen = false;
        setBottomNavVisible(true);
        renderTab(3);
    }

    private LinearLayout subPageRoot(String title, String subtitle) {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        LinearLayout header = row();
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.setBackgroundColor(Color.WHITE);
        TextView back = centeredText("‹", 34, TEXT);
        back.setOnClickListener(v -> {
            if (bottomNav == null) showAuthenticationPage();
            else closeSubPage();
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(52)));
        LinearLayout heading = column();
        heading.setPadding(dp(8), 0, 0, 0);
        TextView titleView = bodyText(title, 19, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(titleView);
        if (!subtitle.isEmpty()) heading.addView(bodyText(subtitle, 12, MUTED));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        return root;
    }

    private void openSubPage(LinearLayout root, int tab) {
        saveActiveChatDraft();if(activeMessageInput!=null)hideChatKeyboard(activeMessageInput);
        Object currentTag = content.getChildCount() > 0 ? content.getChildAt(0).getTag() : null;
        boolean samePage = root.getTag() != null && root.getTag().equals(currentTag);
        if (!showingCommerce && !samePage) rememberPage();
        if(commerce!=null&&!showingCommerce)commerce.externalPage();
        currentCommercePage = showingCommerce;
        chatWindowOpen = false;
        previousTab = tab;
        subPageOpen = true;
        setBottomNavVisible(false);
        content.setPadding(0, 0, 0, 0);
        content.removeAllViews();
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeSubPage() {
        if(currentCommercePage && commerce!=null && commerce.back())return;
        if(restorePreviousPage())return;
        if (walletAssetDetailOpen) {
            walletAssetDetailOpen = false;
            openWalletPage();
            return;
        }
        subPageOpen = false;
        setBottomNavVisible(true);
        content.setPadding(0, 0, 0, bottomNav.getHeight() + dp(24));
        renderTab(previousTab);
    }

    private String mallValue(JSONObject obj, String key) {
        return obj == null || obj.isNull(key) ? "" : obj.optString(key, "");
    }

    private TextView mallAction(LinearLayout parent, String label, Runnable action) {
        TextView button = centeredText(label, 15, BLUE);
        button.setPadding(dp(12), dp(13), dp(12), dp(13));
        button.setBackground(MallUi.surface(this, Color.WHITE, 14, true));
        MallUi.touch(button, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(4));
        parent.addView(button, lp);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout merchantCard(String title, String hint) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(MallUi.surface(this, Color.WHITE, 16, true));
        LinearLayout heading = row();
        TextView name = mallText(title, 16, MallUi.INK, true);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (hint != null && !hint.isEmpty()) heading.addView(mallText(hint, 12, MUTED, false));
        card.addView(heading);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        return card;
    }

    private void merchantMetric(LinearLayout parent, String label, String value) {
        merchantMetric(parent, label, value, null);
    }

    private void merchantMetric(LinearLayout parent, String label, String value, Runnable action) {
        LinearLayout metric = column();
        metric.setPadding(dp(10), dp(9), dp(10), dp(9));
        metric.setBackground(MallUi.surface(this, MallUi.TINT, 12, false));
        metric.addView(mallText(label, 12, MUTED, false));
        TextView amount = mallText(value, 18, MallUi.PRIMARY, true);
        amount.setPadding(0, dp(4), 0, 0);
        metric.addView(amount);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(metric, lp);
        if (action != null) {
            metric.setContentDescription(label + "，" + value);
            metric.setOnClickListener(v -> action.run());
            MallUi.touch(metric, 12);
        }
    }

    private void merchantShortcut(LinearLayout parent, String icon, String label, Runnable action) {
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(5), dp(8), dp(5), dp(7));
        item.setBackground(MallUi.surface(this, Color.WHITE, 11, true));
        item.addView(MallUi.icon(this, icon, MallUi.PRIMARY), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView labelView = centeredText(label, 12, MallUi.INK);
        labelView.setPadding(0, dp(4), 0, 0);
        item.addView(labelView);
        item.setOnClickListener(v -> action.run());
        MallUi.touch(item, 11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(item, lp);
    }

    private EditText mallField(LinearLayout parent, String label, String value, boolean multiline) {
        parent.addView(bodyText(label, 13, MUTED));
        EditText input = formInput(label, value);
        if (multiline) {
            input.setSingleLine(false);
            input.setMinLines(3);
            input.setGravity(Gravity.TOP);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(112)));
        }
        parent.addView(input);
        return input;
    }

    private LinearLayout mallBody(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return body;
    }

    private void mallPost(String action, JSONObject request, TextView button, Runnable done) {
        if (button != null) button.setEnabled(false);
        ApiClient.post(this, action, request, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) { if (button != null) button.setEnabled(true); toast("操作成功"); done.run(); }
            @Override public void onError(String message) { if (button != null) button.setEnabled(true); toast(message); }
        });
    }

    private JSONObject mallRequest(String key, Object value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (Exception ignored) { }
        return result;
    }

    private String mallStatus(String status) {
        switch (status) {
            case "pending": return "待审核 / 待处理";
            case "awaiting_deposit": return "审核通过，待交保证金";
            case "active": return "营业中";
            case "suspended": return "暂停营业";
            case "rejected": return "已驳回";
            case "closing": return "关店处理中";
            case "closed": return "已关闭";
            case "approved": return "审核通过";
            case "shipped": return "已发货 / 已交付";
            case "delivered": return "已完成";
            case "refunded": return "已退款";
            case "cancelled": return "已取消";
            default: return status;
        }
    }

    private void openMerchantCenter() {
        LinearLayout page = subPageRoot("经营概览", "SPX 商家服务");
        LinearLayout body = mallBody(page);
        body.addView(note("正在读取店铺信息…"));
        openSubPage(page, 3);
        ApiClient.get(this, "merchant.me", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) return;
                body.removeAllViews();
                JSONObject payload = (JSONObject) data;
                JSONObject shop = payload.optJSONObject("merchant");
                if (shop == null) {
                    LinearLayout empty = merchantCard("开始经营你的店铺", "申请开店");
                    empty.addView(bodyText("提交资料并通过审核后，就可以发布商品、接收订单和管理咨询群。", 13, MUTED));
                    body.addView(empty);
                    mallAction(body, "申请开店", () -> openMerchantForm(null, true));
                    return;
                }
                String state = shop.optString("status");
                String level = "gold".equals(shop.optString("level")) ? "黄金店铺" : "初级店铺";
                LinearLayout hero = merchantCard(shop.optString("name"), mallStatus(state));
                LinearLayout heroMeta = row();
                heroMeta.addView(bodyText(level, 12, MUTED));
                TextView deposit = bodyText("冻结保证金  " + MallMoney.show(shop.optString("deposit_frozen", "0")) + " SPX001", 12, BLUE);
                deposit.setGravity(Gravity.RIGHT);
                heroMeta.addView(deposit, new LinearLayout.LayoutParams(0, -2, 1));
                hero.addView(heroMeta);
                JSONObject capacity = payload.optJSONObject("product_capacity");
                if (capacity != null && capacity.has("limit") && capacity.optInt("limit") >= 0) {
                    TextView slots = bodyText("已上架 " + capacity.optInt("listed") + "/" + capacity.optInt("limit") + " · 还可上架 " + capacity.optInt("remaining") + " 款", 13, BLUE);
                    slots.setPadding(0, dp(12), 0, 0);
                    hero.addView(slots);
                    hero.addView(bodyText("下架或删除商品后释放上架名额", 11, MUTED));
                }
                String reason = mallValue(shop, "review_reason");
                if (!reason.isEmpty()) {
                    TextView review = bodyText("审核说明  " + reason, 11, MUTED);
                    review.setPadding(0, dp(8), 0, 0);
                    hero.addView(review);
                }
                body.addView(hero);

                JSONObject stats = payload.optJSONObject("statistics");
                LinearLayout metrics = merchantCard("经营数据", "点击标题刷新");
                metrics.getChildAt(0).setMinimumHeight(dp(44));
                metrics.getChildAt(0).setOnClickListener(v -> openMerchantCenter());
                metrics.getChildAt(0).setContentDescription("刷新经营数据");
                LinearLayout metricRow = row();
                merchantMetric(metricRow, "待结算净额", stats == null ? "0" : MallMoney.show(stats.optString("held_amount", "0")));
                merchantMetric(metricRow, "订单总数", stats == null ? "—" : String.valueOf(stats.optInt("order_count")), () -> openMerchantOrders());
                metrics.addView(metricRow);
                LinearLayout metricRow2 = row();
                merchantMetric(metricRow2, "已到账净额", stats == null ? "0" : MallMoney.show(stats.optString("settled_amount", "0")));
                merchantMetric(metricRow2, "商品管理", "进入", () -> openMerchantProducts());
                metrics.addView(metricRow2);
                body.addView(metrics);

                LinearLayout shortcuts = merchantCard("快捷入口", "常用操作");
                LinearLayout shortcutRow = row();
                if ("active".equals(state)) merchantShortcut(shortcutRow, "gift", "发布商品", () -> openMerchantProductForm(null));
                merchantShortcut(shortcutRow, "grid", "商品管理", () -> openMerchantProducts());
                merchantShortcut(shortcutRow, "order", "订单与售后", () -> openMerchantOrders());
                shortcuts.addView(shortcutRow);
                LinearLayout shortcutRow2 = row();
                merchantShortcut(shortcutRow2, "gift", "经营数据", () -> commerce().merchantDashboard());
                merchantShortcut(shortcutRow2, "gift", "扫码核销", () -> commerce().merchantDashboard());
                merchantShortcut(shortcutRow2, "gift", "结算记录", () -> commerce().merchantSettlements());
                shortcuts.addView(shortcutRow2);
                body.addView(shortcuts);

                LinearLayout todo = merchantCard("订单与售后", "及时处理");
                LinearLayout orderTodo = row();
                orderTodo.setMinimumHeight(dp(52));
                orderTodo.addView(MallUi.icon(MainActivity.this, "order", MallUi.PRIMARY), new LinearLayout.LayoutParams(dp(22),dp(22)));
                TextView orderLabel = bodyText("查看订单、发货与售后", 14, TEXT);
                orderLabel.setPadding(dp(10),0,0,0);
                orderTodo.addView(orderLabel, new LinearLayout.LayoutParams(0,-2,1));
                orderTodo.addView(MallUi.icon(MainActivity.this,"chevron",MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));
                orderTodo.setOnClickListener(v -> openMerchantOrders());MallUi.touch(orderTodo,12);
                todo.addView(orderTodo);
                body.addView(todo);

                if ("rejected".equals(state) || "closed".equals(state)) mallAction(body, "修改资料并重新申请", () -> openMerchantForm(shop, true));
                if ("active".equals(state) || "awaiting_deposit".equals(state)) {
                    mallAction(body, "编辑店铺与门店位置", () -> openMerchantForm(shop, false));
                    if ("awaiting_deposit".equals(state)) mallAction(body, "缴纳保证金并开店", () -> confirmMerchantDeposit(shop.optString("level")));
                    if ("active".equals(state) && !"gold".equals(shop.optString("level"))) mallAction(body, "升级黄金店铺", () -> confirmMerchantDeposit("gold"));
                    mallAction(body, "查看我的店铺", () -> openShopPage(shop.optInt("id")));
                    mallAction(body, "申请关店", () -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("申请关店").setMessage("申请后商品下架并停止接单。所有订单、售后和结算结束即可退还保证金。")
                        .setNegativeButton("返回", null).setPositiveButton("申请关店", (d, w) -> mallPost("merchant.close", new JSONObject(), null, () -> openMerchantCenter())).show());
                }
            }
            @Override public void onError(String message) { body.removeAllViews(); body.addView(note(message)); mallAction(body, "重新加载", () -> openMerchantCenter()); }
        });
    }

    private void confirmMerchantDeposit(String level) {
        new AlertDialog.Builder(this).setTitle("确认冻结保证金")
            .setMessage("gold".equals(level) ? "黄金店铺需冻结合计 1,000,000 SPX001，系统只扣除尚未交齐的差额。" : "初级店铺需冻结 100,000 SPX001。")
            .setNegativeButton("返回", null).setPositiveButton("确认缴纳", (d, w) -> mallPost("merchant.deposit.pay", mallRequest("level", level), null, () -> openMerchantCenter())).show();
    }

    private MallMerchantForm merchantForm;
    private void openMerchantForm(JSONObject shop, boolean application) {
        merchantForm = new MallMerchantForm(this, shop, application);
    }
    LinearLayout mallMerchantPage(String title) {
        LinearLayout page = subPageRoot(title, "手机上传资料，地图选择门店");
        page.setBackgroundColor(MallUi.BACKGROUND);
        LinearLayout body = mallBody(page);
        openSubPage(page, 3);
        return body;
    }
    void mallMerchantDone() { merchantForm=null; openMerchantCenter(); }
    void mallMerchantMap(EditText lat, EditText lon, String[] systems, Runnable done) {
        openShopMapPicker(lat,lon,systems,done);
    }

    private void openShopMapPicker(EditText lat, EditText lon, String[] systems, Runnable done) {
        android.webkit.WebView map = new android.webkit.WebView(this);
        map.getSettings().setJavaScriptEnabled(true);
        map.getSettings().setUserAgentString(map.getSettings().getUserAgentString() + " SPXChat (com.spx.chat; shop-map)");
        map.getSettings().setAllowFileAccess(false);
        map.getSettings().setAllowContentAccess(false);
        map.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(android.webkit.WebView view, String url) {
                try {
                    double[] gcj = MapNavigation.toGcj(Double.parseDouble(lat.getText().toString()), Double.parseDouble(lon.getText().toString()), systems[0]);
                    double[] wgs = MapNavigation.gcjToWgs(gcj[0], gcj[1]);
                    map.evaluateJavascript("setLocation(" + wgs[0] + "," + wgs[1] + ")", null);
                } catch (Exception ignored) { }
            }
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) { return true; }
        });
        map.loadUrl("file:///android_asset/shop-map.html");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("拖动地图标记门店")
            .setView(map).setNegativeButton("返回", null).setPositiveButton("使用中心位置", null).create();
        dialog.setOnShowListener(d -> {
            map.setLayoutParams(new FrameLayout.LayoutParams(-1, dp(410)));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> map.evaluateJavascript("JSON.stringify(getLocation())", value -> {
                try {
                    String json = (String) new org.json.JSONTokener(value).nextValue();
                    JSONObject point = new JSONObject(json);
                    lat.setText(String.format(Locale.US, "%.7f", point.getDouble("latitude")));
                    lon.setText(String.format(Locale.US, "%.7f", point.getDouble("longitude")));
                    systems[0] = "wgs84"; done.run(); dialog.dismiss();
                } catch (Exception e) { toast("请等待地图加载后，拖动地图选择门店位置"); }
            }));
        });
        dialog.setOnDismissListener(d -> map.destroy());
        dialog.show();
    }

    private void openShopPage(int id) { openShopPage(id,1); }
    private void openShopPage(int id,int pageNo) {
        LinearLayout page = subPageRoot("店铺", "好物与门店，一起发现");
        page.setTag("shop:" + id + ":" + pageNo);
        page.setBackgroundColor(MallUi.BACKGROUND);
        LinearLayout body = mallBody(page);
        openSubPage(page, 2);
        Map<String, String> query = new HashMap<>(); query.put("id", String.valueOf(id));query.put("page",String.valueOf(pageNo));
        ApiClient.get(this, "shop", query, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) return;
                JSONObject payload = (JSONObject) data, shop = payload.optJSONObject("shop");
                if (shop == null) return;
                renderShopCard(body, shop, false);
                JSONArray products = payload.optJSONArray("products");
                if (products == null || products.length() == 0) mallEmptyState(body, "店铺正在准备好物", "商品上架后会在这里展示");
                LinearLayout productRow = null;
                int productCount = 0;
                if (products != null) for (int i = 0; i < products.length(); i++) {
                    JSONObject p = products.optJSONObject(i); if (p == null) continue;
                    if (productCount % 2 == 0) { productRow = row(); productRow.setGravity(Gravity.TOP); body.addView(productRow); }
                    addMallProduct(productRow, p, 2); productCount++;
                }
                if (productCount % 2 == 1 && productRow != null) {
                    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, 1, 1); space.setMargins(dp(4), 0, dp(4), 0);
                    productRow.addView(new View(MainActivity.this), space);
                }
                body.addView(mallText("第 "+pageNo+" 页",12,MallUi.SECONDARY,false));
                if(pageNo>1)mallAction(body,"上一页",()->openShopPage(id,pageNo-1));
                if(products!=null&&products.length()==60)mallAction(body,"下一页",()->openShopPage(id,pageNo+1));
            }
            @Override public void onError(String message) { body.addView(note(message)); }
        });
    }

    private void renderShopCard(LinearLayout body, JSONObject shop, boolean link) {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(MallUi.surface(this, Color.WHITE, 18, true));
        LinearLayout heading = mallShopTile(shop, false);
        if (link) heading.setOnClickListener(v -> openShopPage(shop.optInt("id")));
        card.addView(heading);
        if (!mallValue(shop, "description").isEmpty()) {
            TextView description = mallText(mallValue(shop, "description"), 13, MallUi.SECONDARY, false);
            description.setLineSpacing(dp(4), 1f); description.setPadding(dp(4), dp(12), dp(4), dp(4));
            card.addView(description);
        }
        if (!mallValue(shop, "business_hours").isEmpty()) {
            TextView hours = mallText("营业时间  " + mallValue(shop, "business_hours"), 12, MallUi.SECONDARY, false);
            hours.setPadding(dp(4), dp(8), dp(4), dp(6)); card.addView(hours);
        }
        if (link) mallAction(card, "进入店铺  ›", () -> openShopPage(shop.optInt("id"))).setBackground(MallUi.surface(this, MallUi.TINT, 12, false));
        if (!mallValue(shop, "address").isEmpty()) {
            LinearLayout location = row(); location.setPadding(dp(10), dp(12), dp(10), dp(12));
            location.setBackground(MallUi.surface(this, MallUi.BACKGROUND, 12, false));
            location.addView(MallUi.icon(this, "pin", MallUi.PRIMARY), new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout address = column(); address.setPadding(dp(10), 0, dp(6), 0);
            TextView label = mallText(mallValue(shop, "address"), 13, MallUi.INK, true);
            label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END); address.addView(label);
            TextView hint = mallText("导航到店  ›", 11, MallUi.PRIMARY, false); hint.setPadding(0, dp(5), 0, 0); address.addView(hint);
            location.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
            location.setOnClickListener(v -> MapNavigation.open(this, shop)); MallUi.touch(location, 12);
            LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(-1, -2); locationParams.setMargins(0, dp(10), 0, 0); card.addView(location, locationParams);
            mallAction(card, "复制门店地址", () -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("门店地址", mallValue(shop, "address"))); toast("地址已复制"); });
        }
        TextView contact = mallAction(card, "联系商家", () -> openMerchantContact(shop));
        contact.setBackground(MallUi.surface(this, MallUi.BACKGROUND, 12, false)); MallUi.touch(contact, 12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(10), 0, dp(10));
        body.addView(card, params);
    }

    private void openShopsPage() {
        LinearLayout page = subPageRoot("店铺广场", "发现好店，逛逛新生活");
        page.setBackgroundColor(MallUi.BACKGROUND);
        LinearLayout body = mallBody(page);
        LinearLayout searchBar = row(); searchBar.setPadding(dp(12), dp(4), dp(4), dp(4));
        searchBar.setBackground(MallUi.surface(this, Color.WHITE, 24, true));
        searchBar.addView(MallUi.icon(this, "search", MallUi.SECONDARY), new LinearLayout.LayoutParams(dp(19), dp(19)));
        EditText query = new EditText(this); query.setSingleLine(true); query.setTextSize(14);
        query.setTextColor(MallUi.INK); query.setHintTextColor(MallUi.SECONDARY); query.setHint("店铺名称或经营类目");
        query.setPadding(dp(8), 0, dp(8), 0); query.setBackgroundColor(Color.TRANSPARENT);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout results = column();
        TextView search = centeredText("搜索", 13, Color.WHITE); search.setBackground(MallUi.surface(this, MallUi.PRIMARY, 22, false));
        searchBar.addView(search, new LinearLayout.LayoutParams(dp(60), dp(40))); body.addView(searchBar);
        TextView label = mallText("全部店铺 · 黄金店铺优先展示", 12, MallUi.SECONDARY, false);
        label.setPadding(dp(4), dp(18), dp(4), dp(6)); body.addView(label);
        body.addView(results);
        int[] shopPage={1};Runnable[] loader={null};loader[0] = () -> {
            Object request = new Object(); results.setTag(request);
            results.removeAllViews(); mallProductSkeleton(results);
            Map<String, String> params = new HashMap<>(); params.put("q", query.getText().toString().trim());params.put("page",""+shopPage[0]);params.put("page_size","20");
            ApiClient.get(this, "shops", params, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (results.getTag() != request) return;
                    if (!(data instanceof JSONArray)) { onError(""); return; }
                    results.removeAllViews();
                    JSONArray shops = (JSONArray) data;
                    if (shops.length() == 0) mallEmptyState(results, "暂时没有匹配的店铺", "换个关键词，再发现一些好店");
                    for (int i = 0; i < shops.length(); i++) {
                        JSONObject shop = shops.optJSONObject(i); if (shop == null) continue;
                        LinearLayout tile = mallShopTile(shop, false); tile.setOnClickListener(v -> openShopPage(shop.optInt("id")));
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(10), 0, 0); results.addView(tile, params);
                    }
                    results.addView(mallText("第 "+shopPage[0]+" 页",12,MallUi.SECONDARY,false));
                    if(shopPage[0]>1)mallAction(results,"上一页",()->{shopPage[0]--;loader[0].run();});
                    if(shops.length()==20)mallAction(results,"下一页",()->{shopPage[0]++;loader[0].run();});
                }
                @Override public void onError(String message) {
                    if (results.getTag() != request) return;
                    results.removeAllViews(); mallEmptyState(results, "店铺服务暂不可用", "请稍后重试，或先浏览商城商品");
                }
            });
        };
        search.setOnClickListener(v -> {shopPage[0]=1;loader[0].run();});
        MallUi.touch(search, 22);
        query.setOnEditorActionListener((v, actionId, event) -> { if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { shopPage[0]=1;loader[0].run(); return true; } return false; });
        page.setFocusableInTouchMode(true); page.requestFocus();
        openSubPage(page, 2); loader[0].run();
    }

    private void openMerchantContact(JSONObject shop) {
        loadUserProfile(shop.optInt("owner_user_id"), new UserProfileCallback() {
            @Override public void onProfile(JSONObject profile) { openFriendProfilePage(profile); }
            @Override public void onError(String message) {
                String phone = mallValue(shop, "contact_phone");
                if (phone.isEmpty()) { toast(message); return; }
                try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))); } catch (Exception e) { toast(phone); }
            }
        });
    }

    private void openMerchantProducts() { commerce().merchantProducts(); }

    private JSONArray mallUrlLines(String text) {
        JSONArray urls = new JSONArray();
        for (String line : text.split("\\r?\\n")) if (!line.trim().isEmpty()) urls.put(line.trim());
        return urls;
    }

    private String mallLines(JSONArray list) {
        if (list == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < list.length(); i++) { if (i > 0) result.append('\n'); result.append(list.optString(i)); }
        return result.toString();
    }

    private void openMerchantProductForm(JSONObject product) { commerce().editProduct(product); }

    private EditText mallUploadTarget;
    private static final int PICK_MALL_IMAGE = 4201;

    private void pickMallImage(EditText target) {
        mallUploadTarget = target;
        ChatGallery.open(this,"image/*",PICK_MALL_IMAGE);
    }

    private void openMerchantOrders() { commerce().merchantOrders(); }

    private void merchantFulfillDialog(JSONObject order) {
        String type = order.optString("order_type");
        LinearLayout form = column(); form.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText first = mallField(form, "physical".equals(type) ? "承运商" : ("local".equals(type) ? "请输入买家出示的核销码" : "交付内容或领取说明"), "", !"physical".equals(type));
        EditText tracking = "physical".equals(type) ? mallField(form, "物流单号", "", false) : null;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("订单 #" + order.optInt("id")).setView(form).setNegativeButton("返回", null).setPositiveButton("确认处理", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONObject request = mallRequest("order_id", order.optInt("id"));
            try {
                request.put("physical".equals(type) ? "carrier" : ("local".equals(type) ? "redeem_code" : "fulfillment_note"), first.getText().toString().trim());
                if (tracking != null) request.put("tracking_number", tracking.getText().toString().trim());
            } catch (Exception e) { return; }
            mallPost("physical".equals(type) ? "merchant.order.ship" : ("local".equals(type) ? "merchant.order.redeem" : "merchant.order.deliver"), request, null, () -> { dialog.dismiss(); openMerchantOrders(); });
        }));
        dialog.show();
    }

    private void addBuyerMallActions(LinearLayout body, JSONObject order) {
        if (order.isNull("merchant_id") || order.optInt("merchant_id") <= 0) return;
        if ("local".equals(order.optString("order_type")) && "pending".equals(order.optString("status"))) body.addView(note("到店核销码：" + order.optString("redeem_code") + "\n请到店接受服务时出示给商家。"));
        if (!mallValue(order, "fulfillment_note").isEmpty()) body.addView(note("交付说明：" + mallValue(order, "fulfillment_note")));
        if ("shipped".equals(order.optString("status"))) mallAction(body, "确认收货 / 确认交付完成", () -> new AlertDialog.Builder(this).setMessage("确认已收到商品或完成交付？确认后进入七天售后期。")
            .setNegativeButton("返回", null).setPositiveButton("确认完成", (d, w) -> mallPost("orders.receive", mallRequest("order_id", order.optInt("id")), null, () -> openOrdersPage())).show());
        if ("requested".equals(order.optString("after_sale_status"))) body.addView(note("售后处理中：" + mallValue(order, "after_sale_reason")));
        if (!mallValue(order, "after_sale_reply").isEmpty()) body.addView(note("平台售后答复：" + mallValue(order, "after_sale_reply")));
        if (!"requested".equals(order.optString("after_sale_status")) && "held".equals(order.optString("settlement_status")) && !"cancelled".equals(order.optString("status")) && !"refunded".equals(order.optString("status"))) mallAction(body, "申请售后", () -> {
            EditText reason = formInput("填写问题和退款原因", "");
            new AlertDialog.Builder(this).setTitle("申请售后").setView(reason).setNegativeButton("返回", null).setPositiveButton("提交", (d, w) -> {
                JSONObject request = mallRequest("order_id", order.optInt("id"));
                try { request.put("reason", reason.getText().toString().trim()); } catch (Exception e) { return; }
                mallPost("orders.after_sale", request, null, () -> openOrdersPage());
            }).show();
        });
    }

    private void openStorePage() {
        LinearLayout root = subPageRoot(t("store"), "发现好物 · 积分兑换");
        root.setBackgroundColor(MallUi.BACKGROUND);
        LinearLayout header = (LinearLayout) root.getChildAt(0);
        LinearLayout records = column();
        records.setGravity(Gravity.CENTER);
        records.addView(MallUi.icon(this, "order", MallUi.INK), new LinearLayout.LayoutParams(dp(21), dp(21)));
        TextView recordsLabel = mallText("订单", 10, MallUi.INK, false);
        recordsLabel.setPadding(0, dp(3), 0, 0);
        records.addView(recordsLabel);
        records.setContentDescription("查看兑换记录和订单");
        records.setOnClickListener(v -> openOrdersPage());
        MallUi.touch(records, 12);
        header.addView(records, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout searchBar = row();
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(16), 0, dp(16), dp(12));
        searchBar.setBackgroundColor(Color.WHITE);
        LinearLayout searchBox = row();
        searchBox.setPadding(dp(12), dp(4), dp(4), dp(4));
        searchBox.setBackground(MallUi.surface(this, Color.rgb(244, 246, 250), 25, true));
        searchBox.addView(MallUi.icon(this, "search", MallUi.SECONDARY), new LinearLayout.LayoutParams(dp(18), dp(18)));
        EditText mallSearch = new EditText(this);
        mallSearch.setSingleLine(true);
        mallSearch.setTextSize(14);
        mallSearch.setTextColor(TEXT);
        mallSearch.setHintTextColor(MUTED);
        mallSearch.setHint("搜索商品或店铺名称");
        mallSearch.setContentDescription("搜索商品或店铺名称");
        mallSearch.setPadding(dp(9), 0, dp(6), 0);
        mallSearch.setBackgroundColor(Color.TRANSPARENT);
        mallSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        mallSearch.setText(mallSearchQuery);
        searchBox.addView(mallSearch, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView searchButton = centeredText("搜索", 13, Color.WHITE);
        searchButton.setBackground(MallUi.primaryButton(this));
        MallUi.touch(searchButton, 22);
        searchBox.addView(searchButton, new LinearLayout.LayoutParams(dp(60), dp(40)));
        searchBar.addView(searchBox, new LinearLayout.LayoutParams(-1, -2));
        root.addView(searchBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = column();
        list.setPadding(dp(12), dp(12), dp(12), dp(24));
        LinearLayout balanceCard = row();
        balanceCard.setGravity(Gravity.CENTER_VERTICAL);
        balanceCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        balanceCard.setBackground(gradient("#182647", "#345491", 24));
        balanceCard.setElevation(dp(2));
        LinearLayout balanceHeading = column();
        balanceHeading.addView(mallText("SPX 好物商城", 20, Color.WHITE, true));
        TextView balanceLabel = mallText("让积分，遇见好物", 11, Color.rgb(202, 216, 255), false);
        balanceLabel.setPadding(0, dp(7), 0, 0);
        balanceHeading.addView(balanceLabel);
        balanceCard.addView(balanceHeading, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout balanceInfo = column();
        balanceInfo.setGravity(Gravity.END);
        balanceInfo.addView(mallText("可用积分", 10, Color.rgb(202, 216, 255), false));
        TextView balance = mallText("读取中…", 23, Color.WHITE, true);
        balance.setSingleLine(true);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(balance, 12, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        balanceInfo.addView(balance, new LinearLayout.LayoutParams(-1, dp(32)));
        balance.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView unit = mallText("SPX001", 9, Color.rgb(202, 216, 255), false);
        balanceInfo.addView(unit);
        balanceCard.addView(balanceInfo, new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(balanceCard, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout shortcuts = row();
        addMallShortcut(shortcuts, "店铺广场", "发现更多好店", false, () -> openShopsPage());
        addMallShortcut(shortcuts, "商家中心", "申请开店 / 管理店铺", true, () -> openMerchantCenter());
        LinearLayout.LayoutParams shortcutsParams = new LinearLayout.LayoutParams(-1, -2);
        shortcutsParams.setMargins(0, dp(10), 0, dp(4));
        list.addView(shortcuts, shortcutsParams);

        LinearLayout goldSection = column();
        goldSection.setVisibility(View.GONE);
        LinearLayout goldHeading = row();
        goldHeading.setPadding(dp(4), dp(14), dp(4), dp(8));
        goldHeading.addView(mallText("黄金好店", 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        goldHeading.addView(mallText("优先推荐", 11, Color.rgb(159, 111, 34), false));
        goldSection.addView(goldHeading);
        HorizontalScrollView goldScroll = new HorizontalScrollView(this);
        goldScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout goldShops = row();
        goldScroll.addView(goldShops);
        goldSection.addView(goldScroll);
        list.addView(goldSection);
        TextView shopStatus = mallText("", 12, MUTED, false);
        shopStatus.setPadding(dp(4), dp(10), dp(4), 0);
        shopStatus.setVisibility(View.GONE);
        list.addView(shopStatus);
        Map<String, String> goldQuery = new HashMap<>(); goldQuery.put("level", "gold");
        MallCatalogCache.get(this, "shops", goldQuery, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) return;
                JSONArray shops = (JSONArray) data;
                goldShops.removeAllViews(); shopStatus.setVisibility(View.GONE);
                for (int i = 0; i < Math.min(6, shops.length()); i++) {
                    JSONObject shop = shops.optJSONObject(i);
                    if (shop == null) continue;
                    LinearLayout card = mallShopTile(shop, true);
                    card.setOnClickListener(v -> openShopPage(shop.optInt("id")));
                    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(212), -2);
                    cardParams.setMargins(0, 0, dp(8), 0);
                    goldShops.addView(card, cardParams);
                }
                goldSection.setVisibility(goldShops.getChildCount() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void onError(String message) {
                shopStatus.setText(message != null && message.contains("未知接口") ? "店铺服务升级中，商品仍可浏览" : "店铺推荐暂时无法加载");
                shopStatus.setVisibility(View.VISIBLE);
            }
        });

        LinearLayout productsHeading = row();
        productsHeading.setGravity(Gravity.CENTER_VERTICAL);
        productsHeading.setPadding(dp(4), dp(14), dp(4), dp(4));
        TextView productTitle = mallText(mallSearchQuery.isEmpty() ? "发现好物" : "搜索结果", 19, MallUi.INK, true);
        productsHeading.addView(productTitle, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout modes = row();
        modes.setGravity(Gravity.CENTER);
        modes.addView(MallUi.icon(this, "grid", MallUi.SECONDARY), new LinearLayout.LayoutParams(dp(17), dp(17)));
        TextView modeLabel = mallText(productColumns + "列", 11, MallUi.SECONDARY, false);
        modeLabel.setPadding(dp(5), 0, 0, 0);
        modes.addView(modeLabel);
        modes.setContentDescription("切换商品排列方式");
        MallUi.touch(modes, 12);
        productsHeading.addView(modes, new LinearLayout.LayoutParams(dp(64), dp(44)));
        list.addView(productsHeading);
        LinearLayout filters = row();
        HorizontalScrollView filtersScroll = new HorizontalScrollView(this);
        filtersScroll.setHorizontalScrollBarEnabled(false);
        filtersScroll.addView(filters);
        list.addView(filtersScroll);
        LinearLayout productGrid = column();
        list.addView(productGrid);
        JSONArray[] loadedProducts = {null};
        modes.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("商品排列")
            .setSingleChoiceItems(new String[]{"大图 · 1列", "双列 · 2列", "紧凑 · 3列"}, productColumns - 1, (dialog, which) -> {
                productColumns = which + 1;
                modeLabel.setText(productColumns + "列");
                if (loadedProducts[0] != null) renderMallProductCards(productGrid, loadedProducts[0], productColumns);
                dialog.dismiss();
            }).show());
        String[] filterValues = {"all", "physical", "digital", "local"};
        String[] filterLabels = {"全部", "实物好物", "数字权益", "到店服务"};
        TextView[] filterButtons = new TextView[filterValues.length];
        for (int i = 0; i < filterValues.length; i++) {
            final String value = filterValues[i];
            TextView button = centeredText(filterLabels[i], 12, MallUi.SECONDARY);
            button.setPadding(dp(14), 0, dp(14), 0);
            styleMallFilter(button, value.equals(mallProductFilter));
            filterButtons[i] = button;
            LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(-2, dp(40));
            filterParams.setMargins(dp(4), 0, dp(4), dp(4));
            filters.addView(button, filterParams);
            button.setOnClickListener(v -> {
                mallProductFilter = value;
                for (int n = 0; n < filterButtons.length; n++) styleMallFilter(filterButtons[n], value.equals(filterValues[n]));
                loadMallProducts(productGrid, loadedProducts, mallSearchQuery);
            });
        }
        Runnable search = () -> {
            mallSearchQuery = mallSearch.getText().toString().trim();
            productTitle.setText(mallSearchQuery.isEmpty() ? "发现好物" : "搜索结果");
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(mallSearch.getWindowToken(), 0);
            mallSearch.clearFocus();
            loadMallProducts(productGrid, loadedProducts, mallSearchQuery);
        };
        searchButton.setOnClickListener(v -> search.run());
        mallSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { search.run(); return true; }
            return false;
        });
        loadMallProducts(productGrid, loadedProducts, mallSearchQuery);
        ApiClient.get(this, "wallet", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) { balance.setText("暂不可用"); unit.setVisibility(View.GONE); return; }
                JSONArray assets = (JSONArray) data;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && "SPX001".equals(asset.optString("symbol")) && "BSC".equalsIgnoreCase(asset.optString("network", "BSC"))) {
                        balance.setText(("SPX001".equals(asset.optString("symbol"))?MallMoney.show(asset.optString("balance", "0")):formatAmount(asset.optString("balance", "0"))));
                        return;
                    }
                }
                balance.setText("0.00");
            }
            @Override public void onError(String message) { balance.setText("暂不可用"); unit.setVisibility(View.GONE); }
        });
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        openSubPage(root, 2);
    }

    private TextView mallText(String text, int size, int color, boolean bold) {
        TextView view = bodyText(text, size, color);
        view.setPadding(0, 0, 0, 0);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private void addMallShortcut(LinearLayout parent, String title, String subtitle, boolean merchant, Runnable action) {
        LinearLayout card = row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(12), dp(8), dp(12));
        card.setBackground(MallUi.surface(this, Color.WHITE, 16, true));
        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(MallUi.surface(this, merchant ? MallUi.TINT : Color.rgb(231, 246, 241), 12, false));
        iconBox.addView(MallUi.icon(this, merchant ? "bag" : "store", merchant ? MallUi.PRIMARY : Color.rgb(49, 128, 103)), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconParams.setMargins(0, 0, dp(8), 0);
        card.addView(iconBox, iconParams);
        LinearLayout text = column();
        TextView heading = mallText(title, 14, MallUi.INK, true);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(heading);
        TextView detail = mallText(subtitle, 10, MUTED, false);
        detail.setPadding(0, dp(5), 0, 0);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(detail);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription(title + "，" + subtitle);
        card.setOnClickListener(v -> action.run());
        MallUi.touch(card, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(merchant ? dp(5) : 0, 0, merchant ? 0 : dp(5), 0);
        parent.addView(card, lp);
    }

    private void styleMallFilter(TextView button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? MallUi.PRIMARY : MallUi.SECONDARY);
        button.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        button.setTextColor(selected ? Color.WHITE : MallUi.SECONDARY);
        button.setBackground(selected ? MallUi.primaryButton(this) : MallUi.surface(this, Color.WHITE, 12, true));
        MallUi.touch(button, 12);
    }

    private LinearLayout mallShopTile(JSONObject shop, boolean compact) {
        boolean gold = "gold".equals(shop.optString("level"));
        LinearLayout tile = row();
        tile.setPadding(dp(12), dp(14), dp(12), dp(14));
        tile.setBackground(MallUi.surface(this, gold ? Color.rgb(255, 251, 241) : Color.WHITE, 16, true));
        String name = mallValue(shop, "name");
        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(MallUi.surface(this, gold ? MallUi.GOLD_TINT : MallUi.TINT, 13, false));
        logo.setClipToOutline(true);
        if (!mallValue(shop, "logo_url").isEmpty()) {
            logo.addView(productImage(shop.optString("logo_url")), new FrameLayout.LayoutParams(-1, -1));
        } else {
            logo.addView(centeredText(name.isEmpty() ? "店" : name.substring(0, name.offsetByCodePoints(0, 1)), 21, gold ? MallUi.GOLD : MallUi.PRIMARY), new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        logoParams.setMargins(0, 0, dp(10), 0);
        tile.addView(logo, logoParams);
        LinearLayout text = column();
        TextView title = mallText(name, 14, MallUi.INK, true);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        LinearLayout meta = row();
        meta.setPadding(0, dp(7), 0, 0);
        meta.addView(MallUi.badge(this, gold ? "黄金店铺" : "初级店铺", gold));
        if (!compact) {
            TextView category = mallText(mallValue(shop, "category"), 11, MallUi.SECONDARY, false);
            category.setPadding(dp(6), 0, 0, 0);
            category.setSingleLine(true); category.setEllipsize(android.text.TextUtils.TruncateAt.END);
            meta.addView(category, new LinearLayout.LayoutParams(0, -2, 1));
        }
        text.addView(meta);
        tile.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (!compact) tile.addView(centeredText("›", 23, MallUi.SECONDARY), new LinearLayout.LayoutParams(dp(22), dp(32)));
        tile.setContentDescription(name + (gold ? "，黄金店铺" : "，初级店铺") + "，查看店铺");
        MallUi.touch(tile, 16);
        return tile;
    }

    private String mallProductTypeLabel(String type) {
        if ("local".equals(type)) return "到店服务";
        if ("digital".equals(type)) return "数字权益";
        if ("physical".equals(type)) return "实物商品";
        return "商品";
    }

    private void mallEmptyState(LinearLayout target, String title, String description) {
        LinearLayout state = column();
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(24), dp(32), dp(24), dp(32));
        state.addView(MallUi.icon(this, "bag", Color.rgb(175, 187, 216)), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView heading = mallText(title, 15, MallUi.INK, true);
        heading.setGravity(Gravity.CENTER); heading.setPadding(0, dp(14), 0, dp(7));
        state.addView(heading);
        TextView detail = mallText(description, 12, MallUi.SECONDARY, false);
        detail.setGravity(Gravity.CENTER); state.addView(detail);
        target.addView(state, new LinearLayout.LayoutParams(-1, -2));
    }

    private void mallProductSkeleton(LinearLayout target) {
        LinearLayout cards = row();
        for (int i = 0; i < 2; i++) {
            LinearLayout card = column();
            card.setPadding(dp(10), dp(10), dp(10), dp(14));
            card.setBackground(MallUi.surface(this, Color.WHITE, 16, false));
            View image = new View(this); image.setBackground(MallUi.surface(this, MallUi.LINE, 10, false));
            card.addView(image, new LinearLayout.LayoutParams(-1, dp(128)));
            for (int line = 0; line < 2; line++) {
                View bar = new View(this); bar.setBackground(MallUi.surface(this, MallUi.BACKGROUND, 5, false));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(line == 0 ? -1 : dp(64), dp(12));
                lp.setMargins(0, dp(12), 0, 0); card.addView(bar, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(4), dp(8), dp(4), 0); cards.addView(card, lp);
        }
        cards.setContentDescription("正在加载商品");
        target.addView(cards);
    }

    private void loadMallProducts(LinearLayout target, JSONArray[] loaded, String query) {
        mallProductsPage=1;
        loadMallProductPage(target,loaded,query,1);
    }

    private void loadMallProductPage(LinearLayout target, JSONArray[] loaded, String query,int page) {
        Object request = new Object();
        target.setTag(request);
        loaded[0] = null;
        target.removeAllViews();
        mallProductSkeleton(target);
        Map<String, String> params = new HashMap<>(); params.put("q", query);params.put("page",String.valueOf(page));params.put("type",mallProductFilter);
        MallCatalogCache.get(this, "products", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (target.getTag() != request) return;
                if (!(data instanceof JSONArray)) { onError(""); return; }
                loaded[0] = (JSONArray) data;
                mallProductsPage=page;mallProductsCount=loaded[0].length();mallLoadedPages=loaded;
                renderMallProductCards(target, loaded[0], productColumns);
            }
            @Override public void onError(String message) {
                if (target.getTag() != request) return;
                target.removeAllViews();
                mallEmptyState(target, "商品暂时无法加载", "请检查网络后重试");
                mallAction(target, "重新加载", () -> loadMallProductPage(target, loaded, query,page));
            }
        });
    }

    private void renderMallProductCards(LinearLayout target, JSONArray products, int columns) {
        target.removeAllViews();
        LinearLayout currentRow = null;
        int count = 0;
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product == null) continue;
            if (!"all".equals(mallProductFilter) && !mallProductFilter.equals(product.optString("product_type"))) continue;
            if (count % columns == 0) {
                currentRow = row();
                currentRow.setGravity(Gravity.TOP);
                target.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
            }
            addMallProduct(currentRow, product, columns);
            count++;
        }
        if (count == 0) {
            mallEmptyState(target, "暂时没有匹配的商品", mallSearchQuery.isEmpty() && "all".equals(mallProductFilter) ? "好物正在准备中，稍后再来看看" : "试试其他分类或搜索关键词");
        } else if (count % columns != 0 && currentRow != null) {
            for (int i = count % columns; i < columns; i++) {
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, 1, 1);
                space.setMargins(dp(4), 0, dp(4), 0);
                currentRow.addView(new View(this), space);
            }
        }
        if(mallLoadedPages!=null && mallLoadedPages[0]==products){
            int shown=mallProductsPage;JSONArray[] pageData=mallLoadedPages;
            target.addView(note("第 "+shown+" 页"));
            if(shown>1)mallAction(target,"上一页",()->loadMallProductPage(target,pageData,mallSearchQuery,shown-1));
            if(mallProductsCount==60)mallAction(target,"下一页",()->loadMallProductPage(target,pageData,mallSearchQuery,shown+1));
        }
    }

    private void addProduct(LinearLayout parent, String name, String desc, String price, String actionText, String imageUrl, int columns) {
        addProduct(parent, 0, name, desc, price, actionText, imageUrl, columns);
    }

    private void addProduct(LinearLayout parent, int productId, String name, String desc, String price, String actionText, String imageUrl, int columns) {
        addProduct(parent, productId, name, desc, price, actionText, imageUrl, columns, null);
    }

    private void addMallProduct(LinearLayout parent, JSONObject product, int columns) {
        String cny = product.isNull("cny_price") ? "" : product.optString("cny_price", "");
        String label = cny.isEmpty() ? "历史 SPX001 " + MallMoney.show(product.optString("spx_price")) : "¥" + MallMoney.show(cny) + " CNY";
        addProduct(parent, product.optInt("id"), product.optString("name"), product.optString("description"),
            label, "查看详情", product.optString("image_url"), columns, product);
    }

    private void addProduct(LinearLayout parent, int productId, String name, String desc, String price, String actionText, String imageUrl, int columns, JSONObject product) {
        LinearLayout card = column();
        card.setBackground(MallUi.surface(this, Color.WHITE, 20, true));
        card.setElevation(dp(1));
        card.setClipToOutline(true);
        MallUi.RatioFrame cover = new MallUi.RatioFrame(this, columns == 1 ? 0.72f : 1f);
        ImageView image = productImage(imageUrl);
        image.setContentDescription(name);
        image.setBackgroundColor(Color.rgb(236, 240, 247));
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (product != null) {
            String type = mallValue(product, "product_type");
            if (!type.isEmpty()) {
                TextView kind = MallUi.badge(this, mallProductTypeLabel(type), false);
                kind.setBackground(MallUi.surface(this, 0xEBFFFFFF, 6, false));
                kind.setTextColor(MallUi.INK);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                lp.setMargins(dp(8), dp(8), dp(8), 0); cover.addView(kind, lp);
            }
        }
        card.addView(cover, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout info = column();
        info.setPadding(dp(columns == 3 ? 8 : 12), dp(10), dp(columns == 3 ? 8 : 12), dp(12));
        TextView productName = mallText(name, columns == 1 ? 17 : (columns == 2 ? 14 : 12), TEXT, true);
        productName.setMaxLines(2);
        productName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (columns > 1) productName.setMinLines(2);
        info.addView(productName);
        String merchantName = product == null ? "" : mallValue(product, "merchant_name");
        TextView description = mallText(merchantName.isEmpty() ? desc.replace("\n", " · ").trim() : merchantName, columns == 1 ? 12 : 11, MallUi.SECONDARY, false);
        description.setSingleLine(true);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        description.setPadding(0, dp(5), 0, dp(8));
        info.addView(description);
        if (product != null && "gold".equals(product.optString("merchant_level"))) {
            TextView goldBadge = MallUi.badge(this, "黄金店铺", true);
            LinearLayout.LayoutParams goldParams = new LinearLayout.LayoutParams(-2, -2);
            goldParams.setMargins(0, 0, 0, dp(7)); info.addView(goldBadge, goldParams);
        }
        String amount = price.trim();
        String unit = "SPX001 积分";
        if (amount.startsWith("历史 SPX001")) {
            amount = amount.substring("历史 SPX001".length()).trim();
            unit = "SPX001 历史价";
        } else if (amount.endsWith(" CNY")) {
            amount = amount.substring(0, amount.length() - 4).trim();
            unit = "CNY 固定价";
        } else {
            int unitIndex = amount.indexOf("SPX001");
            if (unitIndex >= 0) amount = amount.substring(0, unitIndex).trim();
        }
        LinearLayout priceRow = row();
        LinearLayout priceText = column();
        TextView priceView = mallText(amount, columns == 1 ? 25 : (columns == 2 ? 21 : 16), MallUi.PRIMARY, true);
        priceView.setSingleLine(true);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(priceView, 10, columns == 1 ? 25 : (columns == 2 ? 21 : 16), 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        priceText.addView(priceView, new LinearLayout.LayoutParams(-1, dp(29)));
        TextView priceUnit = mallText(unit, 10, MallUi.SECONDARY, false);
        priceText.addView(priceUnit);
        priceRow.addView(priceText, new LinearLayout.LayoutParams(0, -2, 1));
        if (columns == 2) {
            FrameLayout actionIcon = new FrameLayout(this);
            actionIcon.setBackground(MallUi.surface(this, MallUi.TINT, 12, false));
            actionIcon.addView(MallUi.icon(this, "arrow", MallUi.PRIMARY), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            priceRow.addView(actionIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        }
        info.addView(priceRow);
        if (columns == 1) {
            TextView action = centeredText(actionText, 13, BLUE);
            action.setBackground(MallUi.surface(this, MallUi.TINT, 12, false));
            MallUi.touch(action, 12);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(40));
            actionParams.setMargins(0, dp(10), 0, 0);
            info.addView(action, actionParams);
            action.setOnClickListener(v -> openProductDetail(productId, name, desc, price, imageUrl));
        }
        card.addView(info);
        card.setContentDescription(name + "，" + price + "，查看详情");
        card.setOnClickListener(v -> openProductDetail(productId, name, desc, price, imageUrl));
        MallUi.touch(card, 18);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(4), dp(8), dp(4), 0);
        parent.addView(card, params);
    }

    private ImageView productImage(String imageUrl) {
        ImageView view=new ImageView(this);view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setBackground(round(Color.rgb(238,242,255),12));MallImages.bind(view,resolveAssetUrl(imageUrl),false);return view;
    }

    private void openProductDetail(String name, String desc, String price, String imageUrl) {
        openProductDetail(0, name, desc, price, imageUrl);
    }

    private void openProductDetail(int productId, String name, String desc, String price, String imageUrl) {
        if (productId <= 0) { toast(t("invalid_product")); return; }
        LinearLayout loading = subPageRoot(t("product_detail"), t("reading_product"));
        loading.setTag("product:" + productId);
        loading.addView(note(t("loading_product")), new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(loading, activeTab);
        pendingProductLoading=loading;
        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(productId));
        ApiClient.get(this, "product", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (loading.getParent() != content) return;
                if (!(data instanceof JSONObject)) { toast(t("product_detail_failed")); return; }
                renderProductDetail((JSONObject) data);
            }
            @Override public void onError(String message) {
                if (loading.getParent() != content) return;
                toast(message); closeSubPage();
            }
        });
    }

    private void renderProductDetail(JSONObject product) { commerce().product(product); }

    private void loadCheckoutAddresses(TextView target, JSONObject[] selectedAddress) {
        ApiClient.get(this, "wallet.address", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray) || ((JSONArray) data).length() == 0) {
                    target.setText(t("checkout_no_address"));
                    target.setOnClickListener(v -> openAddressPage());
                    return;
                }
                JSONArray addresses = (JSONArray) data;
                ArrayList<JSONObject> options = new ArrayList<>();
                String[] labels = new String[addresses.length()];
                int selected = -1;
                for (int i = 0; i < addresses.length(); i++) {
                    JSONObject address = addresses.optJSONObject(i);
                    if (address == null) continue;
                    options.add(address);
                    labels[i] = address.optString("name") + " · " + address.optString("phone") + "\n"
                            + address.optString("province") + address.optString("city") + address.optString("district") + address.optString("detail");
                    if (address.optInt("is_default") == 1) selected = i;
                }
                if (selectedAddress[0] == null && selected >= 0) selectedAddress[0] = addresses.optJSONObject(selected);
                updateCheckoutAddressLabel(target, selectedAddress[0]);
                 target.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this).setTitle(t("select_address"))
                        .setSingleChoiceItems(labels, selectedAddress[0] == null ? -1 : addressIndex(options, selectedAddress[0].optInt("id")), (dialog, which) -> {
                            selectedAddress[0] = options.get(which);
                            updateCheckoutAddressLabel(target, selectedAddress[0]);
                            dialog.dismiss();
                          }).setNeutralButton(t("select_new_address"), (dialog, which) -> openAddressPage()).show());
            }
            @Override public void onError(String message) { target.setText(t("checkout_address_load_failed")); }
        });
    }

    private int addressIndex(ArrayList<JSONObject> addresses, int addressId) {
        for (int i = 0; i < addresses.size(); i++) if (addresses.get(i).optInt("id") == addressId) return i;
        return -1;
    }

    private void updateCheckoutAddressLabel(TextView target, JSONObject address) {
        if (address == null) return;
        target.setText(t("checkout_address_label", address.optString("name"), address.optString("phone"),
                address.optString("province"), address.optString("city"), address.optString("district"), address.optString("detail")));
    }

    private final class PageCacheStatus {
        final TextView label;
        final Map<String,String> errors=new HashMap<>();
        final Set<String> cached=new HashSet<>();
        PageCacheStatus(LinearLayout root,Runnable refresh){
            label=bodyText("",12,MUTED);label.setPadding(dp(18),dp(8),dp(18),dp(8));label.setVisibility(View.GONE);
            label.setOnClickListener(v->refresh.run());root.addView(label,new LinearLayout.LayoutParams(-1,-2));
        }
        void state(String action,boolean showingCache,String error){
            if(showingCache)cached.add(action);else cached.remove(action);
            if(error.isEmpty())errors.remove(action);else errors.put(action,error);
            label.setVisibility(cached.isEmpty()&&errors.isEmpty()?View.GONE:View.VISIBLE);
            label.setText(!errors.isEmpty()?t(cached.isEmpty()?"cache_load_failed":"cache_read_failed_retry",errors.values().iterator().next()):t("cache_showing"));
        }
    }
    private void cachedPageGet(String action,PageCacheStatus status,ApiClient.Callback callback) {
        AccountPageCache.get(this,currentUserId,action,false,callback,(cached,error)->status.state(action,cached,error));
    }

    private String stakingSymbol = "SPX001";
    private void openStakingPage() {
        LinearLayout root = subPageRoot(t("staking_title"), t("staking_subtitle_asset", displayAssetSymbol(stakingSymbol)));
        root.setTag("staking");
        PageCacheStatus cacheStatus=new PageCacheStatus(root,()->{AccountPageCache.invalidate(this);openStakingPage();});
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(dp(16), dp(16), dp(16), dp(24));
        addFinancialPendingNotice(list,"staking.create");
        // 币种切换：胶囊轨道（选中项白底浮起）
        LinearLayout symbolSwitch = row();
        symbolSwitch.setPadding(dp(4), dp(4), dp(4), dp(4));
        symbolSwitch.setBackground(round(Color.rgb(237, 239, 244), 36));
        for (String symbolOption : new String[]{"SPX001", "XKRS"}) {
            boolean activeOption = stakingSymbol.equals(symbolOption);
            TextView chip = centeredText(symbolOption, 14, activeOption ? TEXT : MUTED);
            if (activeOption) chip.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.setPadding(0, dp(11), 0, dp(11));
            chip.setBackground(round(activeOption ? Color.WHITE : Color.TRANSPARENT, 32));
            chip.setOnClickListener(v -> {
                if (stakingSymbol.equals(symbolOption)) return;
                stakingSymbol = symbolOption;
                openStakingPage();
            });
            symbolSwitch.addView(chip, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-1, -2);
        switchParams.setMargins(0, 0, 0, dp(14));
        list.addView(symbolSwitch, switchParams);
        final String symbolLabel = displayAssetSymbol(stakingSymbol);

        // ---- 余额主卡 ----
        LinearLayout heroCard = column();
        heroCard.setPadding(dp(22), dp(18), dp(22), dp(16));
        heroCard.setBackground(round(Color.WHITE, 20));
        heroCard.addView(bodyText("可质押余额", 12, MUTED));
        LinearLayout availRow = row();
        availRow.setGravity(android.view.Gravity.BOTTOM);
        final TextView availBig = bodyText("—", 32, TEXT);
        availBig.setTypeface(null, android.graphics.Typeface.BOLD);
        availRow.addView(availBig, new LinearLayout.LayoutParams(-2, -2));
        TextView availUnit = bodyText(symbolLabel, 13, MUTED);
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(-2, -2);
        unitParams.setMargins(dp(8), 0, 0, dp(5));
        availRow.addView(availUnit, unitParams);
        heroCard.addView(availRow, new LinearLayout.LayoutParams(-1, -2));
        View heroDivider = new View(this);
        heroDivider.setBackgroundColor(Color.rgb(238, 240, 245));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(14), 0, dp(12));
        heroCard.addView(heroDivider, dividerParams);
        LinearLayout heroStats = row();
        final TextView stakedValue = bodyText("0", 15, TEXT);
        final TextView profitValue = bodyText("0", 15, Color.rgb(12, 150, 105));
        for (int i = 0; i < 2; i++) {
            LinearLayout statBox = row();
            statBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
            statBox.addView(bodyText(i == 0 ? "已质押" : "待发收益", 12, MUTED));
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2);
            valueParams.setMargins(dp(8), 0, 0, 0);
            statBox.addView(i == 0 ? stakedValue : profitValue, valueParams);
            heroStats.addView(statBox, new LinearLayout.LayoutParams(0, -2, 1));
        }
        heroCard.addView(heroStats, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, -2);
        heroParams.setMargins(0, 0, 0, dp(18));
        list.addView(heroCard, heroParams);

        // ---- 奖励额度 ----
        addStakingSection(list, "奖励额度", "SPX001".equals(stakingSymbol) ? "新质押按剩余额度计息" : "2 亿分红池");
        LinearLayout quotaCard = column();
        quotaCard.setPadding(dp(22), dp(16), dp(22), dp(16));
        quotaCard.setBackground(round(Color.WHITE, 20));
        LinearLayout quotaTop = row();
        LinearLayout quotaLeft = column();
        quotaLeft.addView(bodyText("总奖励额度", 12, MUTED));
        final TextView quotaTotal = bodyText("—", 26, TEXT);
        quotaTotal.setTypeface(null, android.graphics.Typeface.BOLD);
        quotaLeft.addView(quotaTotal);
        quotaTop.addView(quotaLeft, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout quotaRight = column();
        quotaRight.setGravity(android.view.Gravity.RIGHT);
        quotaRight.addView(bodyText("剩余可用", 12, MUTED));
        final TextView quotaRemain = bodyText("—", 20, Color.rgb(12, 150, 105));
        quotaRemain.setTypeface(null, android.graphics.Typeface.BOLD);
        quotaRight.addView(quotaRemain);
        quotaTop.addView(quotaRight, new LinearLayout.LayoutParams(-2, -2));
        quotaCard.addView(quotaTop, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout quotaTrack = row();
        final View quotaFill = new View(this);
        quotaFill.setBackground(round(BLUE, 5));
        final View quotaRest = new View(this);
        quotaRest.setBackground(round(Color.rgb(238, 240, 245), 5));
        quotaTrack.addView(quotaFill, new LinearLayout.LayoutParams(0, dp(10), 0.001f));
        quotaTrack.addView(quotaRest, new LinearLayout.LayoutParams(0, dp(10), 0.999f));
        LinearLayout.LayoutParams quotaTrackParams = new LinearLayout.LayoutParams(-1, -2);
        quotaTrackParams.setMargins(0, dp(12), 0, dp(10));
        quotaCard.addView(quotaTrack, quotaTrackParams);

        LinearLayout quotaFoot = row();
        final TextView quotaPaid = bodyText("已发放 —", 11, MUTED);
        final TextView quotaReserved = bodyText("已预扣 —", 11, MUTED);
        quotaFoot.addView(quotaPaid, new LinearLayout.LayoutParams(0, -2, 1));
        quotaFoot.addView(quotaReserved, new LinearLayout.LayoutParams(-2, -2));
        quotaCard.addView(quotaFoot, new LinearLayout.LayoutParams(-1, -2));
        final TextView quotaNote = bodyText("", 11, Color.rgb(178, 184, 196));
        quotaNote.setPadding(0, dp(4), 0, 0);
        quotaCard.addView(quotaNote, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams quotaParams = new LinearLayout.LayoutParams(-1, -2);
        quotaParams.setMargins(0, 0, 0, dp(18));
        list.addView(quotaCard, quotaParams);

        // ---- XKRS 起押阶梯（仅 XKRS） ----
        final LinearLayout ladderCard = column();
        final LinearLayout ladderDots = row();
        final TextView ladderPill = bodyText("当前 —", 12, Color.rgb(188, 122, 22));
        final TextView ladderFootLeft = bodyText("累计新增销毁 —", 11, MUTED);
        final TextView ladderFootRight = bodyText("", 11, BLUE);
        if (!"SPX001".equals(stakingSymbol)) {
            addStakingSection(list, "起押门槛", "销毁越多，门槛越低");
            ladderCard.setPadding(dp(22), dp(16), dp(22), dp(16));
            ladderCard.setBackground(round(Color.WHITE, 20));
            ladderCard.addView(ladderPill, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(-1, -2);
            dotsParams.setMargins(0, dp(14), 0, dp(6));
            ladderCard.addView(ladderDots, dotsParams);
            View ladderDivider = new View(this);
            ladderDivider.setBackgroundColor(Color.rgb(238, 240, 245));
            LinearLayout.LayoutParams ladderDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
            ladderDividerParams.setMargins(0, dp(10), 0, dp(10));
            ladderCard.addView(ladderDivider, ladderDividerParams);
            LinearLayout ladderFoot = row();
            ladderFoot.addView(ladderFootLeft, new LinearLayout.LayoutParams(0, -2, 1));
            ladderFoot.addView(ladderFootRight, new LinearLayout.LayoutParams(-2, -2));
            ladderCard.addView(ladderFoot, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams ladderParams = new LinearLayout.LayoutParams(-1, -2);
            ladderParams.setMargins(0, 0, 0, dp(18));
            list.addView(ladderCard, ladderParams);
        }
        TextView stakeMeta = bodyText(t("staking_records_loading"), 12, MUTED);
        stakeMeta.setPadding(0, dp(8), 0, dp(4));
        list.addView(stakeMeta, new LinearLayout.LayoutParams(-1, -2));
        cachedPageGet("wallet",cacheStatus,new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) return;
                JSONArray assets = (JSONArray) data;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && stakingSymbol.equals(asset.optString("symbol"))) {
                        availBig.setText(MallMoney.show(asset.optString("stakeable_balance", "0")));
                        return;
                    }
                }
                availBig.setText("0.00");
            }
            @Override public void onError(String message) {
                availBig.setText(t("stakeable_balance_unavailable"));
            }
        });
        LinearLayout rules = column();
        rules.setPadding(dp(16), dp(14), dp(16), dp(14));
        rules.setBackground(round(Color.WHITE, 14));
        rules.addView(bodyText(t("staking_rules"), 15, TEXT));
        TextView rulesText = bodyText(t("staking_plans_loading"), 13, MUTED);
        rules.addView(rulesText);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, -2);
        ruleParams.setMargins(0, dp(12), 0, dp(8));
        list.addView(rules, ruleParams);
        list.addView(section(t("select_staking_plan")));
        LinearLayout planList = column();
        list.addView(planList, new LinearLayout.LayoutParams(-1, -2));
        Map<String,String> stakingPlansParams=new HashMap<>();stakingPlansParams.put("symbol",stakingSymbol);
        ApiClient.get(this,"staking.plans",stakingPlansParams,new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                planList.removeAllViews();
                if (!(data instanceof JSONObject)) {
                    rulesText.setText(t("no_staking_plans"));
                    planList.addView(note(t("check_later")));
                    return;
                }
                JSONObject payload = (JSONObject) data;
                JSONArray plans = payload.optJSONArray("plans");
                JSONObject phase = payload.optJSONObject("phase");
                if (phase == null) {
                    quotaTotal.setText("—");
                    quotaRemain.setText("—");
                    quotaNote.setText("当前没有开启的质押期");
                } else {
                    String total = formatAmount(phase.optString("total_quota", "0"));
                    String reserved = phase.optString("reserved_quota", "0");
                    quotaTotal.setText(compactStakingAmount(total));
                    quotaRemain.setText(compactStakingAmount(phase.optString("remaining_quota", "0")));
                    quotaPaid.setText("已发放 " + compactStakingAmount(phase.optString("paid_quota", "0")));
                    quotaReserved.setText("已预扣 " + compactStakingAmount(reserved));
                    String poolName = phase.optString("name", "");
                    quotaNote.setText(poolName.isEmpty() ? "剩余 = 总额度 − 已预扣" : poolName + " · 剩余 = 总额度 − 已预扣");
                    try {
                        double totalValue = Double.parseDouble(total);
                        double reservedValue = Double.parseDouble(reserved);
                        float ratio = totalValue > 0 ? (float) Math.max(0, Math.min(1, reservedValue / totalValue)) : 0f;
                        quotaFill.setLayoutParams(new LinearLayout.LayoutParams(0, dp(10), Math.max(0.001f, ratio)));
                        quotaRest.setLayoutParams(new LinearLayout.LayoutParams(0, dp(10), Math.max(0.001f, 1f - ratio)));
                    } catch (Exception ignored) { }
                }
                JSONObject burn = payload.optJSONObject("burn");
                if (burn != null && !"SPX001".equals(stakingSymbol)) {
                    buildStakingLadder(ladderDots, ladderPill, ladderFootLeft, ladderFootRight, burn);
                }
                if (plans == null || plans.length() == 0) {
                    if (phase == null) rulesText.setText(t("no_staking_plans"));
                    planList.addView(note(t("no_staking_plans")));
                    return;
                }
                StringBuilder summaryText = new StringBuilder();
                for (int i = 0; i < plans.length(); i++) {
                    JSONObject plan = plans.optJSONObject(i);
                    if (plan == null) continue;
                    if (summaryText.length() > 0) summaryText.append('\n');
                    String planSymbolLabel = displayAssetSymbol(plan.optString("symbol", stakingSymbol));
                    summaryText.append(t("staking_plan_summary_asset", plan.optInt("plan_days"), formatAmount(plan.optString("apy")), compactStakingAmount(plan.optString("minimum_amount")), planSymbolLabel));
                    String planStepValue = plan.optString("step_amount", "0");
                    if (new java.math.BigDecimal(planStepValue).compareTo(java.math.BigDecimal.ONE) > 0) summaryText.append("\n").append(t("staking_rule_step_asset", compactStakingAmount(planStepValue), planSymbolLabel));
                    String planMaxValue = plan.optString("max_per_user", "");
                    if (!planMaxValue.isEmpty() && !"null".equals(planMaxValue)) summaryText.append("\n").append(t("staking_rule_max_asset", compactStakingAmount(planMaxValue), planSymbolLabel));
                    addStakePlan(planList, plan);
                }
                summaryText.append("\n").append(t("staking_rule_generic"));
                rulesText.setText(summaryText.toString());
            }
            @Override public void onError(String message) { rulesText.setText(t("staking_rules_load_failed")); }
        });
        LinearLayout recordsHeader = row();
        recordsHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView recordsTitle = section(t("my_staking_records"));
        recordsTitle.setOnClickListener(v -> openStakingRecordsPage());
        recordsHeader.addView(recordsTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView earningsLink = bodyText(t("staking_earnings_title") + " ›", 13, BLUE);
        earningsLink.setPadding(0, 0, dp(4), 0);
        earningsLink.setOnClickListener(v -> openStakingEarningsPage());
        recordsHeader.addView(earningsLink, new LinearLayout.LayoutParams(-2, -2));
        list.addView(recordsHeader, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout recordsList=column();list.addView(recordsList,new LinearLayout.LayoutParams(-1,-2));
        Map<String,String> stakingRecordsParams=new HashMap<>();stakingRecordsParams.put("symbol",stakingSymbol);
        ApiClient.get(this,"staking",stakingRecordsParams,new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) return;
                JSONArray records = (JSONArray) data;
                recordsList.removeAllViews();
                double total = 0, stakedSum = 0, profitSum = 0;
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record == null) continue;
                    total += record.optDouble("amount", 0);
                    if ("active".equals(record.optString("status"))) stakedSum += record.optDouble("amount", 0);
                    profitSum += record.optDouble("paid_dividend", 0);
                    String dailyIncome = t("staking_dividend_schedule");
                    try {
                        java.math.BigDecimal daily = new java.math.BigDecimal(record.optString("amount", "0"))
                                .multiply(new java.math.BigDecimal(record.optString("apy", "0")))
                                .divide(new java.math.BigDecimal("36500"), 8, java.math.RoundingMode.DOWN);
                        java.math.BigDecimal everyTenDays = new java.math.BigDecimal(record.optString("amount", "0"))
                                .multiply(new java.math.BigDecimal(record.optString("apy", "0")))
                                .multiply(new java.math.BigDecimal("10"))
                                .divide(new java.math.BigDecimal("36500"), 8, java.math.RoundingMode.DOWN);
                        dailyIncome = t("staking_income_summary_asset", formatAmount(daily.toPlainString()), formatAmount(everyTenDays.toPlainString()), formatAmount(record.optString("paid_dividend", "0")), displayAssetSymbol(record.optString("symbol", stakingSymbol)));
                    } catch (Exception ignored) { }
                    addRecord(recordsList, record, t("staking_lock_days", record.optString("plan_days")), t("points_amount_asset", compactStakingAmount(record.optString("amount")), displayAssetSymbol(record.optString("symbol", stakingSymbol))), dailyIncome);
                }
                stakedValue.setText(formatAmount(String.valueOf(stakedSum)) + " " + displayAssetSymbol(stakingSymbol));
                profitValue.setText("+" + formatAmount(String.valueOf(profitSum)) + " " + displayAssetSymbol(stakingSymbol));
                stakeMeta.setText(t("staking_record_count", records.length()));
                if (records.length() == 0) recordsList.addView(note(t("no_staking_records")));
            }
            @Override public void onError(String message) { stakeMeta.setText(message); }
        });
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 2);
    }

    private void openStakingRecordsPage() {
        LinearLayout root = subPageRoot(t("staking_records_title"), t("staking_records_subtitle"));
        LinearLayout content = column();
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout filters = row();
        filters.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout list = column();
        list.setPadding(dp(16), dp(4), dp(16), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        content.addView(filters, new LinearLayout.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addView(bodyText(t("loading"), 14, MUTED));

        final JSONArray[] recordsHolder = {new JSONArray()};
        final int[] selectedFilter = {0};
        final String[] labels = {t("all"), t("staking_status_active"), t("staking_status_done")};
        final Runnable[] render = new Runnable[1];
        final Runnable[] buildFilters = new Runnable[1];
        buildFilters[0] = () -> {
            filters.removeAllViews();
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                boolean chosen = selectedFilter[0] == i;
                TextView chip = bodyText(labels[i], 13, chosen ? Color.WHITE : MUTED);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(12), dp(8), dp(12), dp(8));
                chip.setBackground(round(chosen ? Color.rgb(45, 91, 255) : Color.rgb(240, 242, 247), 16));
                chip.setOnClickListener(v -> { selectedFilter[0] = index; buildFilters[0].run(); render[0].run(); });
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, -2, 1);
                chipParams.setMargins(dp(4), 0, dp(4), 0);
                filters.addView(chip, chipParams);
            }
        };
        render[0] = () -> {
            list.removeAllViews();
            int shown = 0;
            for (int i = 0; i < recordsHolder[0].length(); i++) {
                JSONObject record = recordsHolder[0].optJSONObject(i);
                if (record == null) continue;
                boolean running = "active".equals(record.optString("status"));
                if (selectedFilter[0] == 1 && !running) continue;
                if (selectedFilter[0] == 2 && running) continue;
                String label = displayAssetSymbol(record.optString("symbol", "SPX001"));
                LinearLayout card = roundCard();
                LinearLayout head = row();
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = bodyText(t("staking_lock_days", record.optString("plan_days")) + " · " + label, 16, TEXT);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                head.addView(bodyText(running ? t("staking_status_active") : t("staking_status_done"), 12,
                        running ? Color.rgb(46, 154, 111) : MUTED), new LinearLayout.LayoutParams(-2, -2));
                card.addView(head, new LinearLayout.LayoutParams(-1, -2));
                card.addView(bodyText(t("staking_matures_at", record.optString("matures_at", "-")), 12, MUTED));
                card.addView(bodyText(t("staking_amount_label", formatAmount(record.optString("amount", "0")), label), 14, TEXT));
                card.addView(bodyText(t("staking_earnings_expected", formatAmount(record.optString("expected_reward", "0")), label), 13, BLUE));
                final int detailRecordId = record.optInt("id");
                card.setOnClickListener(v -> openStakingDetailPage(detailRecordId));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, 0, 0, dp(10));
        list.addView(card, cardParams);
        shown++;
            }
            if (shown == 0) list.addView(note(t("no_staking_records")));
        };
        buildFilters[0].run();
        ApiClient.get(this, "staking", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (data instanceof JSONArray) recordsHolder[0] = (JSONArray) data;
                render[0].run();
            }
            @Override public void onError(String message) {
                list.removeAllViews();
                list.addView(retryState(message, () -> openStakingRecordsPage()));
            }
        });
        openSubPage(root, 4);
    }


    private void openStakingDetailPage(int recordId) {
        LinearLayout root = subPageRoot("质押详情", "本金、利息发放与到期信息");
        LinearLayout content = column();
        content.setPadding(dp(16), dp(12), dp(16), dp(28));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(bodyText("加载中…", 14, MUTED));
        openSubPage(root, 4);

        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(recordId));
        ApiClient.get(this, "staking.detail", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) {
                    content.removeAllViews();
                    content.addView(note("质押记录不存在"));
                    return;
                }
                renderStakingDetail(content, (JSONObject) data);
            }
            @Override public void onError(String message) {
                content.removeAllViews();
                content.addView(retryState(message, () -> openStakingDetailPage(recordId)));
            }
        });
    }

    private void renderStakingDetail(LinearLayout content, JSONObject d) {
        content.removeAllViews();
        String symbol = d.optString("symbol", "SPX001");
        String status = d.optString("status", "active");
        boolean active = "active".equals(status);
        int paid = d.optInt("periods_paid");
        int total = Math.max(1, d.optInt("periods_total"));

        LinearLayout hero = roundCard();
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(bodyText(symbol + " 锁仓", 14, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(bodyText(active ? "进行中" : "已完成", 12, active ? Color.rgb(46, 154, 111) : MUTED),
                new LinearLayout.LayoutParams(-2, -2));
        hero.addView(head);
        hero.addView(bodyText(formatAmount(d.optString("amount", "0")) + " " + symbol, 30, TEXT));
        hero.addView(bodyText("年化 " + formatAmount(d.optString("apy", "0")) + "% · 锁定 " + d.optInt("plan_days") + " 天", 13, MUTED));
        addCardWithMargin(content, hero);

        LinearLayout progress = roundCard();
        progress.addView(dialogRow("利息发放进度", paid + " / " + total + " 期"));
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        float ratio = Math.max(0f, Math.min(1f, paid / (float) total));
        View fill = new View(this);
        fill.setBackground(round(BLUE, 4));
        View rest = new View(this);
        rest.setBackground(round(Color.rgb(238, 240, 245), 4));
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(8), Math.max(0.02f, ratio)));
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(8), Math.max(0.02f, 1f - ratio)));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(-1, -2);
        trackParams.setMargins(0, dp(6), 0, dp(10));
        progress.addView(track, trackParams);
        progress.addView(dialogRow("已发利息", formatAmount(d.optString("paid_dividend", "0")) + " " + symbol));
        progress.addView(dialogRow("待发利息", formatAmount(d.optString("remaining_reward", "0")) + " " + symbol));
        progress.addView(dialogRow("应发合计", formatAmount(d.optString("expected_reward", "0")) + " " + symbol));
        addCardWithMargin(content, progress);

        LinearLayout times = roundCard();
        times.addView(dialogRow("起息时间", d.optString("created_at", "-")));
        times.addView(dialogRow("到期时间", d.optString("matures_at", "-")));
        String nextPayout = d.optString("next_payout_at", "");
        if (!nextPayout.isEmpty() && !"null".equals(nextPayout)) times.addView(dialogRow("下期发放", nextPayout));
        String lastPaid = d.optString("last_paid_at", "");
        if (!lastPaid.isEmpty() && !"null".equals(lastPaid)) times.addView(dialogRow("最近发放", lastPaid));
        addCardWithMargin(content, times);

        JSONObject phase = d.optJSONObject("phase");
        if (phase != null) {
            LinearLayout pool = roundCard();
            pool.addView(dialogRow("分红奖池", phase.optString("name", "-")));
            pool.addView(dialogRow("奖池总额度", formatAmount(phase.optString("total_quota", "0")) + " " + symbol));
            addCardWithMargin(content, pool);
        }

        content.addView(section("分红明细"));
        JSONArray dividends = d.optJSONArray("dividends");
        if (dividends == null || dividends.length() == 0) {
            content.addView(note("还没有利息发放记录，每 10 天发放一次"));
        } else {
            LinearLayout list = roundCard();
            for (int i = 0; i < dividends.length(); i++) {
                JSONObject row = dividends.optJSONObject(i);
                if (row == null) continue;
                list.addView(dialogRow("第 " + row.optInt("period_no") + " 期",
                        formatAmount(row.optString("amount", "0")) + "  ·  " + row.optString("paid_at", "")));
            }
            addCardWithMargin(content, list);
        }

        if (d.optBoolean("can_redeem")) {
            TextView redeem = centeredText("到期赎回本金", 16, Color.WHITE);
            redeem.setPadding(dp(12), dp(14), dp(12), dp(14));
            redeem.setBackground(round(BLUE, 14));
            LinearLayout.LayoutParams redeemParams = new LinearLayout.LayoutParams(-1, -2);
            redeemParams.setMargins(0, dp(6), 0, 0);
            redeem.setOnClickListener(v -> redeemStaking(recordId(d), content));
            content.addView(redeem, redeemParams);
        }
    }

    private int recordId(JSONObject d) {
        return d.optInt("id");
    }

    private void redeemStaking(int recordId, LinearLayout content) {
        new AlertDialog.Builder(this)
                .setTitle("赎回本金")
                .setMessage("到期后赎回会把本金退回钱包，确认操作？")
                .setNegativeButton(t("cancel"), null)
                .setPositiveButton("确认赎回", (dialog, which) -> {
                    JSONObject body = new JSONObject();
                    try { body.put("id", recordId); } catch (Exception ignored) { return; }
                    ApiClient.post(this, "staking.redeem", body, new ApiClient.Callback() {
                        @Override public void onSuccess(Object data) { toast("本金已退回钱包"); openStakingDetailPage(recordId); }
                        @Override public void onError(String error) { toast(error); }
                    });
                }).show();
    }

    private void addCardWithMargin(LinearLayout parent, android.view.View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(10));
        parent.addView(card, params);
    }

    private void openStakingEarningsPage() {
        LinearLayout root = subPageRoot(t("staking_earnings_title"), t("staking_earnings_subtitle"));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addView(bodyText(t("loading"), 14, MUTED));
        ApiClient.get(this, "staking.earnings", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                list.removeAllViews();
                if (!(data instanceof JSONObject)) { list.addView(note(t("no_staking_records"))); return; }
                JSONObject payload = (JSONObject) data;
                JSONObject totals = payload.optJSONObject("totals");
                JSONArray records = payload.optJSONArray("records");
                if (totals != null) {
                    java.util.Iterator<String> keys = totals.keys();
                    while (keys.hasNext()) {
                        String symbolCode = keys.next();
                        JSONObject total = totals.optJSONObject(symbolCode);
                        if (total == null) continue;
                        String label = displayAssetSymbol(symbolCode);
                        LinearLayout card = roundCard();
                        card.addView(bodyText(label, 14, MUTED));
                        card.addView(bodyText(t("staking_earnings_expected", formatAmount(total.optString("expected", "0")), label), 16, BLUE));
                        card.addView(bodyText(t("staking_earnings_paid", formatAmount(total.optString("paid", "0")), label), 13, MUTED));
                        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
                        cardParams.setMargins(0, 0, 0, dp(10));
                        list.addView(card, cardParams);
                    }
                }
                if (records == null || records.length() == 0) { list.addView(note(t("no_staking_records"))); return; }
                list.addView(section(t("staking_earnings_records")));
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record == null) continue;
                    String label = displayAssetSymbol(record.optString("symbol", "SPX001"));
                    LinearLayout card = roundCard();
                    card.addView(bodyText(t("staking_lock_days", record.optString("plan_days")) + " · " + label, 15, TEXT));
                    card.addView(bodyText(t("staking_matures_at", record.optString("matures_at", "-")), 12, MUTED));
                    card.addView(bodyText(t("staking_earnings_expected", formatAmount(record.optString("expected_reward", "0")), label), 14, BLUE));
                    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
                    cardParams.setMargins(0, 0, 0, dp(8));
                    list.addView(card, cardParams);
                }
            }
            @Override public void onError(String message) {
                list.removeAllViews();
                list.addView(retryState(message, () -> openStakingEarningsPage()));
            }
        });
        openSubPage(root, 4);
    }

    private void addStakePlan(LinearLayout parent, JSONObject plan) {
        int days = plan.optInt("plan_days");
        String rate = formatAmount(plan.optString("apy", "0"));
        String minimum = formatAmount(plan.optString("minimum_amount", "0"));
        String symbol = displayAssetSymbol(plan.optString("symbol", stakingSymbol));
        int periods = Math.max(1, (int) Math.ceil(days / 10.0));

        LinearLayout card = column();
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        card.setBackground(round(Color.WHITE, 20));

        LinearLayout top = row();
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView daysView = bodyText(days + " 天", 20, TEXT);
        daysView.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(daysView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView rateView = bodyText(rate + "%", 22, BLUE);
        rateView.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(rateView, new LinearLayout.LayoutParams(-2, -2));
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sub = row();
        sub.addView(bodyText("每 10 天发息 · 共 " + periods + " 期 · 到期还本", 12, MUTED),
                new LinearLayout.LayoutParams(0, -2, 1));
        sub.addView(bodyText("年化", 11, MUTED), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.setMargins(0, dp(6), 0, dp(14));
        card.addView(sub, subParams);

        LinearLayout bottom = row();
        bottom.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bottom.addView(bodyText("起押 " + compactStakingAmount(minimum) + " " + symbol, 12, MUTED),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView stake = centeredText("立即质押", 14, Color.WHITE);
        stake.setPadding(dp(20), dp(10), dp(20), dp(10));
        stake.setBackground(round(BLUE, 12));
        stake.setOnClickListener(v -> showStakeDialog(plan));
        bottom.addView(stake, new LinearLayout.LayoutParams(-2, -2));
        card.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(12));
        parent.addView(card, params);
    }

    private void addStakingSection(LinearLayout parent, String title, String hint) {
        LinearLayout row = row();
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView titleView = bodyText(title, 14, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        if (hint != null && !hint.isEmpty()) row.addView(bodyText(hint, 11, MUTED), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(2), 0, dp(8));
        parent.addView(row, params);
    }

    private String compactStakingAmount(String raw) {
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(raw);
            java.math.BigDecimal yi = new java.math.BigDecimal("100000000");
            java.math.BigDecimal wan = new java.math.BigDecimal("10000");
            if (value.compareTo(yi) >= 0) return value.divide(yi, 2, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString() + " 亿";
            if (value.compareTo(wan) >= 0) return value.divide(wan, 1, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString() + " 万";
            return value.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return raw;
        }
    }

    private void buildStakingLadder(LinearLayout dots, TextView pill, TextView footLeft, TextView footRight, JSONObject burn) {
        String currentMin = burn.optString("tier_min", "");
        pill.setText("当前 " + compactStakingAmount(currentMin) + " 起");
        dots.removeAllViews();
        JSONArray tiers = burn.optJSONArray("tiers");
        int count = tiers == null ? 0 : tiers.length();
        for (int i = 0; i < count; i++) {
            JSONObject tier = tiers.optJSONObject(i);
            if (tier == null) continue;
            boolean active = currentMin.equals(tier.optString("min_stake"));
            LinearLayout node = column();
            node.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (active) {
                shape.setColor(BLUE);
            } else {
                shape.setColor(Color.WHITE);
                shape.setStroke(dp(1), Color.rgb(206, 211, 221));
            }
            dot.setBackground(shape);
            int dotSize = active ? dp(16) : dp(12);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotParams.setMargins(0, active ? 0 : dp(2), 0, dp(8));
            node.addView(dot, dotParams);
            node.addView(bodyText(compactStakingAmount(tier.optString("threshold", "0")), 10, MUTED));
            node.addView(bodyText(compactStakingAmount(tier.optString("min_stake", "0")), 12,
                    active ? TEXT : Color.rgb(168, 174, 186)));
            dots.addView(node, new LinearLayout.LayoutParams(0, -2, 1));
        }
        footLeft.setText("累计新增销毁 " + compactStakingAmount(burn.optString("amount", "0")));
        String nextMin = burn.optString("next_min", "");
        if (!nextMin.isEmpty() && !"null".equals(nextMin)) {
            footRight.setText("再销毁 " + compactStakingAmount(burn.optString("remaining_to_next", ""))
                    + " → " + compactStakingAmount(nextMin) + " 起");
        } else {
            footRight.setText("已是最高档");
        }
    }

    private void addRecord(LinearLayout parent, JSONObject record, String title, String amount, String income) {
        LinearLayout card = row();
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(round(Color.WHITE, 14));
        LinearLayout text = column();
        text.addView(bodyText(title + "  ·  " + amount, 15, TEXT));
        text.addView(bodyText(income, 13, MUTED));
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        String status = record.optString("status", "active");
        card.addView(bodyText("redeemed".equals(status) ? t("redeemed") : record.optInt("can_redeem") == 1 ? t("auto_processing") : "active".equals(status) ? t("locked") : status, 13,
                "redeemed".equals(status) ? MUTED : Color.rgb(46, 154, 111)));
        card.setOnClickListener(v -> openStakingDetailPage(record.optInt("id")));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        if (record.optInt("can_redeem") != 1) return;
        TextView autoRedeem = centeredText(t("staking_auto_redeem"), 13, BLUE);
        autoRedeem.setPadding(dp(8), dp(8), dp(8), dp(8));
        parent.addView(autoRedeem, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showStakeRecordDetails(JSONObject record) {
        String amount = formatAmount(record.optString("amount", "0"));
        String paid = formatAmount(record.optString("paid_dividend", "0"));
        String apy = formatAmount(record.optString("apy", "0"));
        String status = "redeemed".equals(record.optString("status")) ? t("redeemed") : "active".equals(record.optString("status")) ? t("locked") : record.optString("status", t("unknown"));
        java.math.BigDecimal daily = java.math.BigDecimal.ZERO;
        try { daily = new java.math.BigDecimal(record.optString("amount", "0")).multiply(new java.math.BigDecimal(record.optString("apy", "0"))).divide(new java.math.BigDecimal("36500"), 8, java.math.RoundingMode.DOWN); } catch (Exception ignored) { }
        String details = t("staking_record_details", status, record.optString("plan_days", "0"), amount, apy,
                formatAmount(daily.toPlainString()), formatAmount(daily.multiply(java.math.BigDecimal.TEN).toPlainString()), paid, record.optString("created_at", ""));
        new AlertDialog.Builder(this).setTitle(t("staking_detail")).setMessage(details).setPositiveButton(t("know"), null).show();
    }

    private void addFinancialPendingNotice(LinearLayout list,String action) {
        if(!new FinancialRequests(this,currentUserId).pending(action))return;
        TextView notice=bodyText(t("financial_pending_short"),13,BLUE);
        notice.setPadding(dp(12),dp(12),dp(12),dp(12));notice.setBackground(round(Color.rgb(239,243,255),10));
        notice.setOnClickListener(v->showFinancialRecovery(action));list.addView(notice,new LinearLayout.LayoutParams(-1,-2));
    }

    private boolean recoverPendingFinancial(String action) {
        if(!new FinancialRequests(this,currentUserId).pending(action))return false;
        showFinancialRecovery(action);return true;
    }

    private void showFinancialRecovery(String action) {
        final FinancialRequests requests=new FinancialRequests(this,currentUserId);
        final ApiClient.SessionStamp session=new ApiClient.SessionStamp(this);
        final View page=content==null||content.getChildCount()==0?null:content.getChildAt(0);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(t("financial_check_title")).setMessage(t("financial_pending"))
                .setNegativeButton(t("cancel"),null).setPositiveButton(t("financial_check"),null).create();
        trackAccountDialog(dialog);
        dialog.setOnShowListener(shownDialog->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!session.valid(this)){dialog.dismiss();return;}
            android.widget.Button button=dialog.getButton(AlertDialog.BUTTON_POSITIVE);button.setEnabled(false);button.setText(t("financial_checking"));
            requests.resolve(action,new FinancialRequests.Callback(){
                private boolean valid(){return session.valid(MainActivity.this)&&!isFinishing()&&!isDestroyed();}
                public void confirmed(JSONObject result){if(!valid())return;dialog.dismiss();toast(t("staking.create".equals(action)?"staking_submitted":"withdraw_submitted"));refreshFinancialPage(page,action);}
                public void notSubmitted(){if(!valid())return;dialog.dismiss();toast(t("financial_not_submitted"));refreshFinancialPage(page,action);}
                public void rejected(String message){uncertain(message);}
                public void uncertain(String message){if(!valid()||!dialog.isShowing())return;button.setEnabled(true);button.setText(t("financial_check"));dialog.setMessage(t("financial_pending")+"\n\n"+message);}
            });
        }));
        dialog.show();
    }

    private void refreshFinancialPage(View page,String action) {
        if(page==null||content==null||content.getChildCount()==0||content.getChildAt(0)!=page)return;
        if("staking".equals(page.getTag()))openStakingPage();
        else if("wallet".equals(page.getTag()))openWalletPage();
    }

    private void submitFinancial(String action,JSONObject body,Dialog dialog,TextView button) {
        final ApiClient.SessionStamp session=new ApiClient.SessionStamp(this);
        final View page=content==null||content.getChildCount()==0?null:content.getChildAt(0);
        final String original=button.getText().toString();
        button.setEnabled(false);button.setText(t("financial_submitting"));
        if(ApiClient.token(this).isEmpty()||currentUserId<=0){dialog.dismiss();toast(t("financial_identity_loading"));return;}
        new FinancialRequests(this,currentUserId).submit(action,body,new FinancialRequests.Callback(){
            private boolean valid(){return session.valid(MainActivity.this)&&!isFinishing()&&!isDestroyed();}
            public void confirmed(JSONObject result){if(!valid())return;dialog.dismiss();toast(t("staking.create".equals(action)?"staking_submitted":"withdraw_submitted"));refreshFinancialPage(page,action);}
            public void notSubmitted(){if(!valid())return;dialog.dismiss();toast(t("financial_not_submitted"));refreshFinancialPage(page,action);}
            public void rejected(String message){if(!valid()||!dialog.isShowing())return;button.setEnabled(true);button.setText(original);toast(message);}
            public void uncertain(String message){
                if(!valid()||!dialog.isShowing())return;
                button.setEnabled(true);button.setText(t("financial_check"));
                button.setOnClickListener(v->{dialog.dismiss();showFinancialRecovery(action);});
                toast(t("financial_pending_short"));
            }
        });
    }

    private void showStakeDialog(JSONObject plan) {
        if (financialDialogBlocked()) return;
        stakeDialogOpenedAt = android.os.SystemClock.elapsedRealtime();
        if(recoverPendingFinancial("staking.create"))return;
        final String planSymbol = plan.optString("symbol", "SPX001");
        ApiClient.get(this, "wallet", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                String stakeable = "0";
                if (data instanceof JSONArray) {
                    JSONArray assets = (JSONArray) data;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && planSymbol.equals(asset.optString("symbol"))) {
                            stakeable = asset.optString("stakeable_balance", "0");
                            break;
                        }
                    }
                }
                showStakeDialog(plan, stakeable);
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private LinearLayout balanceRow(String label, TextView valueView) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.addView(bodyText(label, 13, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private LinearLayout dialogRow(String label, String value) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(bodyText(label, 13, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(bodyText(value, 14, TEXT), new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void showStakeDialog(JSONObject plan, String availableStakeable) {
        EditText amount = formInput(t("staking_amount_asset", displayAssetSymbol(plan.optString("symbol", "SPX001"))), "");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        amount.setHint(t("staking_amount_example"));
        final int days = plan.optInt("plan_days");
        final int planId = plan.optInt("id");
        final String minimum = plan.optString("minimum_amount", "0");
        final String apy = plan.optString("apy", "0");
        final String planStep = plan.optString("step_amount", "0");
        final String planMax = plan.optString("max_per_user", "");
        final String planSymbol = displayAssetSymbol(plan.optString("symbol", "SPX001"));
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
        String maturityDate = dateFormat.format(new java.util.Date(System.currentTimeMillis() + days * 86400000L));
        LinearLayout content = column();
        content.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout detail = column();
        detail.setPadding(dp(12), dp(10), dp(12), dp(10));
        detail.setBackground(round(Color.rgb(245, 247, 250), 10));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.setMargins(0, dp(8), 0, dp(8));

        final TextView rewardView = bodyText("—", 15, BLUE);
        detail.addView(dialogRow(t("staking_detail_period"), t("staking_days_value", days)));
        detail.addView(dialogRow(t("staking_detail_apy"), formatAmount(apy) + "%"));
        detail.addView(dialogRow(t("staking_detail_maturity"), maturityDate));
        LinearLayout rewardRow = row();
        rewardRow.setGravity(Gravity.CENTER_VERTICAL);
        rewardRow.setPadding(0, dp(6), 0, 0);
        rewardRow.addView(bodyText(t("staking_detail_reward"), 13, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        rewardRow.addView(rewardView, new LinearLayout.LayoutParams(-2, -2));
        detail.addView(rewardRow);

        content.addView(amount);
        // 快捷金额：起押 / 2 倍 / 5 倍 / 10 倍 / 全部可质押（都取整）
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(0, dp(6), 0, dp(2));
        final java.math.BigDecimal quickMin = new java.math.BigDecimal(minimum.isEmpty() ? "0" : minimum);
        java.math.BigDecimal quickMaxValue = new java.math.BigDecimal(availableStakeable.isEmpty() ? "0" : availableStakeable);
        final java.math.BigDecimal quickStep = new java.math.BigDecimal(planStep == null || planStep.isEmpty() ? "0" : planStep);
        if (quickStep.signum() > 0 && quickMaxValue.compareTo(quickStep) >= 0) {
            quickMaxValue = quickMaxValue.divide(quickStep, 0, java.math.RoundingMode.DOWN).multiply(quickStep);
        }
        final java.math.BigDecimal quickMax = quickMaxValue;
        final String[] quickLabels = {"起押", "2 倍", "5 倍", "10 倍", "全部"};
        final java.math.BigDecimal[] quickValues = {
                quickMin,
                quickMin.multiply(new java.math.BigDecimal("2")),
                quickMin.multiply(new java.math.BigDecimal("5")),
                quickMin.multiply(new java.math.BigDecimal("10")),
                quickMax.setScale(0, java.math.RoundingMode.DOWN)
        };
        for (int qi = 0; qi < quickLabels.length; qi++) {
            final java.math.BigDecimal quickValue = quickValues[qi];
            TextView chip = centeredText(quickLabels[qi], 12, Color.rgb(45, 91, 255));
            chip.setPadding(dp(6), dp(8), dp(6), dp(8));
            chip.setBackground(round(Color.rgb(238, 242, 255), 10));
            chip.setOnClickListener(v -> {
                if (quickValue.signum() <= 0) { toast("可质押余额不足"); return; }
                amount.setText(quickValue.setScale(0, java.math.RoundingMode.DOWN).toPlainString());
                amount.setSelection(amount.getText().length());
            });
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, -2, 1);
            chipLp.setMargins(dp(3), 0, dp(3), 0);
            quickRow.addView(chip, chipLp);
        }
        content.addView(quickRow, new LinearLayout.LayoutParams(-1, -2));
        content.addView(bodyText(t("stakeable_balance_asset", formatAmount(availableStakeable), planSymbol), 13, Color.rgb(38, 115, 91)));
        content.addView(bodyText("必须是 " + compactStakingAmount(planStep) + " 的整数倍（如 110 万、150 万）", 12, MUTED));
        content.addView(detail, detailParams);
        content.addView(bodyText(t("staking_no_early_redeem"), 12, MUTED));

        Runnable updateEstimate = () -> {
            try {
                java.math.BigDecimal principal = new java.math.BigDecimal(amount.getText().toString().trim());
                java.math.BigDecimal total = principal.multiply(new java.math.BigDecimal(apy))
                        .multiply(java.math.BigDecimal.valueOf(days))
                        .divide(new java.math.BigDecimal("36500"), 8, java.math.RoundingMode.DOWN);
                rewardView.setText(t("points_amount_asset", "+" + formatAmount(total.toPlainString()), planSymbol));
            } catch (Exception ignored) {
                rewardView.setText("—");
            }
        };
        amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateEstimate.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        final ApiClient.SessionStamp formSession=new ApiClient.SessionStamp(this);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(t("confirm_staking")).setView(content)
                .setNegativeButton(t("cancel"),null).setPositiveButton(t("confirm_staking"),null).create();
        trackAccountDialog(dialog);
        dialog.setOnShowListener(shownDialog->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!formSession.valid(this)){dialog.dismiss();return;}
            String value = amount.getText().toString().trim();
            try {
                java.math.BigDecimal principal = new java.math.BigDecimal(value);
                if (principal.signum() <= 0) { toast(t("staking_amount_invalid")); return; }
                java.math.BigDecimal minAmount = new java.math.BigDecimal(minimum);
                if (principal.compareTo(minAmount) < 0) { toast(t("staking_min_amount", formatAmount(minimum), planSymbol)); return; }
                java.math.BigDecimal stepAmount = new java.math.BigDecimal(planStep.isEmpty() ? "0" : planStep);
                if (stepAmount.signum() > 0 && principal.remainder(stepAmount).signum() != 0) {
                    toast(t("staking_rule_step_asset", formatAmount(planStep), planSymbol)); return;
                }
                if (!planMax.isEmpty() && !"null".equals(planMax) && principal.compareTo(new java.math.BigDecimal(planMax)) > 0) {
                    toast(t("staking_rule_max_asset", formatAmount(planMax), planSymbol)); return;
                }
                if (principal.compareTo(new java.math.BigDecimal(availableStakeable)) > 0) {
                    toast(t("stakeable_balance_insufficient")); return;
                }
            } catch (Exception ignored) { toast(t("staking_amount_integer_required")); return; }
            JSONObject body = new JSONObject();
            try { body.put("plan_id", planId); body.put("amount", new java.math.BigInteger(value).toString()); } catch (Exception ignored) { return; }
            submitFinancial("staking.create",body,dialog,dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        }));
        dialog.show();
    }

    private void openWalletPage() {
        walletAssetDetailOpen = false;
        LinearLayout root = subPageRoot(t("wallet"), t("wallet_subtitle"));
        root.setTag("wallet");
        PageCacheStatus cacheStatus=new PageCacheStatus(root,()->{AccountPageCache.invalidate(this);openWalletPage();});
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(dp(18), dp(16), dp(18), dp(28));
        addFinancialPendingNotice(list,"wallet.withdraw");
        LinearLayout overview = column();
        overview.setPadding(dp(22), dp(20), dp(22), dp(20));
        overview.setBackground(gradient("#173A3F", "#27645C", 18));
        overview.addView(bodyText(t("total_assets"), 13, Color.rgb(203, 225, 220)));
        TextView totalValue = bodyText("$ --", 34, Color.WHITE);
        totalValue.setTypeface(null, android.graphics.Typeface.BOLD);
        totalValue.setSingleLine(true);
        totalValue.setContentDescription(t("wallet_balance_hidden"));
        totalValue.setOnClickListener(v -> {
            walletBalancesHidden = !walletBalancesHidden;
            preferences.edit().putBoolean("wallet_balances_hidden", walletBalancesHidden).apply();
            Object value = totalValue.getTag();
            totalValue.setText(walletBalancesHidden ? "$ ••••" : (value == null ? "$ --" : String.valueOf(value)));
            totalValue.setContentDescription(walletBalancesHidden ? t("wallet_balance_hidden") : t("wallet_balance_visible"));
        });
        walletBalancesHidden = preferences.getBoolean("wallet_balances_hidden", true);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(totalValue, 16, 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams totalParams = new LinearLayout.LayoutParams(-1, dp(58));
        totalParams.topMargin = dp(4);
        overview.addView(totalValue, totalParams);
        TextView valuationHint = bodyText(t("wallet_valuation_hint"), 12, Color.rgb(203, 225, 220));
        overview.addView(valuationHint);
        list.addView(overview);
        LinearLayout actions = row();
        actions.setPadding(0, dp(14), 0, dp(8));
        addWalletAction(actions, t("wallet_deposit_action"), BLUE, () -> showTransferDialog("deposit"));
        addWalletAction(actions, t("wallet_withdraw_action"), Color.rgb(35, 139, 105), () -> showTransferDialog("withdraw"));
        list.addView(actions);
        LinearLayout bindingCard = row();
        bindingCard.setGravity(Gravity.CENTER_VERTICAL);
        bindingCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        bindingCard.setBackground(round(Color.WHITE, 12));
        LinearLayout bindingText = column();
        TextView bindingTitle = bodyText(t("wallet_binding"), 15, TEXT);
        bindingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView bindingAddress = bodyText(t("wallet_binding_loading"), 12, MUTED);
        String[] bindingState = {"loading"};
        bindingText.addView(bindingTitle);
        bindingText.addView(bindingAddress);
        bindingCard.addView(bindingText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView bindAction = centeredText(t("reading"), 13, BLUE);
        bindingCard.setEnabled(false);
        bindAction.setEnabled(false);
        bindingCard.addView(bindAction, new LinearLayout.LayoutParams(-2, dp(42)));
        bindingCard.setOnClickListener(v -> {
            if ("unbound".equals(bindingState[0])) showBindBscWalletDialog();
            else if ("bound".equals(bindingState[0])) copyText(normalizeOptionalText(preferences.getString(walletBindingKey(), "")), t("bound_bsc_address"));
            else if ("error".equals(bindingState[0])) openWalletPage();
        });
        bindAction.setOnClickListener(v -> bindingCard.performClick());
        LinearLayout.LayoutParams bindingParams = new LinearLayout.LayoutParams(-1, -2);
        bindingParams.setMargins(dp(4), dp(2), dp(4), dp(8));
        list.addView(bindingCard, bindingParams);
        cachedPageGet("wallet.binding",cacheStatus,new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                String address = data instanceof JSONObject ? normalizeOptionalText(((JSONObject) data).optString("bound_address", "")) : "";
                preferences.edit().putString(walletBindingKey(), address).apply();
                if (address.isEmpty()) {
                    bindingState[0] = "unbound";
                    bindingAddress.setText(t("wallet_unbound_hint"));
                    bindAction.setText(t("tap_to_bind"));
                } else {
                    bindingState[0] = "bound";
                    bindingAddress.setText(address);
                    bindAction.setText(t("bound_copy"));
                }
                bindingCard.setEnabled(true);
                bindAction.setEnabled(true);
            }
            @Override public void onError(String message) {
                bindingState[0] = "error";
                bindingAddress.setText(t("wallet_binding_unavailable"));
                bindAction.setText(t("retry_loading"));
                bindingCard.setEnabled(true);
                bindAction.setEnabled(true);
            }
        });
        TextView assetsTitle = bodyText(t("assets"), 17, TEXT);
        assetsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        assetsTitle.setPadding(0, dp(18), 0, dp(8));
        list.addView(assetsTitle);
        LinearLayout assetRows=column();list.addView(assetRows,new LinearLayout.LayoutParams(-1,-2));
        int[] assetRenderEpoch={0};
        cachedPageGet("wallet",cacheStatus,new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) return;
                JSONArray assets = (JSONArray) data;
                final int epoch=++assetRenderEpoch[0];
                loadSpx001UsdPrice((price, quoteError) -> {
                    if(epoch!=assetRenderEpoch[0])return;
                    assetRows.removeAllViews();
                    java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                    boolean hasSpxBalance = false;
                    int shown = 0;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String symbol = asset.optString("symbol", "");
                        if (!"SPX001".equals(symbol) && !"USDT".equals(symbol) && !"XKRS".equals(symbol)) continue;
                        java.math.BigDecimal amount;
                        try { amount = new java.math.BigDecimal(asset.optString("balance", "0")); }
                        catch (Exception ignored) { amount = java.math.BigDecimal.ZERO; }
                        // 服务端已带 price_usd（AVE 实时价），优先使用；缺失时退回 SPX001 报价或 USDT=1
                        java.math.BigDecimal unitPrice;
                        String serverPrice = asset.optString("price_usd", "");
                        if (!serverPrice.isEmpty() && !"null".equals(serverPrice)) {
                            try { unitPrice = new java.math.BigDecimal(serverPrice); }
                            catch (Exception ignored) { unitPrice = java.math.BigDecimal.ZERO; }
                        } else {
                            unitPrice = "USDT".equals(symbol) ? java.math.BigDecimal.ONE
                                    : ("SPX001".equals(symbol) ? java.math.BigDecimal.valueOf(price) : java.math.BigDecimal.ZERO);
                        }
                        String valuation = unitPrice.signum() > 0 ? "$" + formatUsd(amount.multiply(unitPrice)) : t("usd_quote_unavailable");
                        String visibleAmount = "SPX001".equals(symbol) ? MallMoney.show(amount.toPlainString()) : formatAmount(amount.toPlainString());
                        String visibleValuation = valuation;
                        addToken(assetRows, symbol, walletBalancesHidden ? "••••" : visibleAmount, walletBalancesHidden ? "••••" : visibleValuation, unitPrice.signum() > 0 ? t("wallet_unit_price", formatTokenPrice(symbol, unitPrice)) : t("bsc_mainnet"), asset.optString("stakeable_balance", "0"));
                        if ("SPX001".equals(symbol)) hasSpxBalance = amount.signum() > 0;
                        if (unitPrice.signum() > 0) total = total.add(amount.multiply(unitPrice));
                        shown++;
                    }
                    if (shown == 0) assetRows.addView(note(t("no_bsc_assets")));
                    if (hasSpxBalance && price <= 0) {
                        totalValue.setText("$ --");
                        valuationHint.setText(t("spx_quote_unavailable_total") + (quoteError.isEmpty() ? "" : t("error_with_message", quoteError)));
                    } else {
                        String totalText = "$ " + formatUsd(total);
                        totalValue.setTag(totalText);
                        totalValue.setText(walletBalancesHidden ? "$ ••••" : totalText);
                        valuationHint.setText(price > 0 ? t("wallet_valuation_hint") : t("wallet_spx_quote_unavailable", quoteError));
                    }
                });
            }
            @Override public void onError(String message) {
                assetRows.removeAllViews();assetRows.addView(retryState(message, () -> openWalletPage()));
            }
        });
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void openWalletTransactionsPage(String symbol) {
        walletAssetDetailOpen = true;
        String displaySymbol = displayAssetSymbol(symbol);
        LinearLayout root = subPageRoot(t("wallet_transaction_title", displaySymbol), t("wallet_transactions_subtitle"));
        TextView balanceSummary = bodyText(t("wallet_balance_loading"), 17, TEXT);
        balanceSummary.setTypeface(null, android.graphics.Typeface.BOLD);
        balanceSummary.setPadding(dp(18), dp(14), dp(18), dp(8));
        root.addView(balanceSummary);
        ApiClient.get(this, "wallet", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) { balanceSummary.setText(t("wallet_balance_unavailable")); return; }
                JSONArray assets = (JSONArray) data;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && symbol.equals(asset.optString("symbol"))) {
                        if ("SPX001".equals(symbol)) {
                            balanceSummary.setText(t("available_balance_with_stakeable", ("SPX001".equals(asset.optString("symbol"))?MallMoney.show(asset.optString("balance", "0")):formatAmount(asset.optString("balance", "0"))), MallMoney.show(asset.optString("stakeable_balance", "0")), displaySymbol));
                        } else {
                            balanceSummary.setText(t("available_balance", ("SPX001".equals(asset.optString("symbol"))?MallMoney.show(asset.optString("balance", "0")):formatAmount(asset.optString("balance", "0"))), displaySymbol));
                        }
                        return;
                    }
                }
                balanceSummary.setText(t("available_balance", "0.00", displaySymbol));
            }
            @Override public void onError(String message) { balanceSummary.setText(t("wallet_balance_unavailable")); }
        });
        LinearLayout filters = row();
        filters.setPadding(dp(16), dp(10), dp(16), dp(10));
        String[] labels = {t("all"), t("transaction_increase"), t("transaction_decrease")};
        String[] selected = {t("all")};
        LinearLayout list = column();
        list.setPadding(dp(16), dp(8), dp(16), dp(24));
        JSONArray[] transactions = {new JSONArray()};
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            int shown = 0;
            for (int i = 0; i < transactions[0].length(); i++) {
                JSONObject item = transactions[0].optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("type");
                if (!symbol.equals(item.optString("symbol"))) continue;
                int direction = walletTransactionDirection(type);
                if (t("transaction_increase").equals(selected[0]) && direction <= 0) continue;
                if (t("transaction_decrease").equals(selected[0]) && direction >= 0) continue;
                String title = walletTransactionLabel(type);
                LinearLayout card = column();
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                card.setBackground(round(Color.WHITE, 12));
                LinearLayout top = row();
                top.addView(bodyText(title + " · " + displayAssetSymbol(item.optString("symbol")), 15, TEXT), new LinearLayout.LayoutParams(0, dp(30), 1));
                 String status = item.optString("status", "pending");
                 top.addView(bodyText(walletTransactionStatusLabel(status), 12, status.equals("completed") ? Color.rgb(37, 145, 103) : MUTED));
                card.addView(top);
                String sign = direction > 0 ? "+" : (direction < 0 ? "−" : "");
                card.addView(bodyText(sign + ("SPX001".equals(symbol)?MallMoney.show(item.optString("amount", "0")):formatAmount(item.optString("amount", "0"))) + " " + displaySymbol, 19,
                        direction > 0 ? Color.rgb(35, 139, 105) : (direction < 0 ? Color.rgb(190, 75, 70) : BLUE)));
                 if(item.optInt("mall_order_id")>0 || item.optInt("mall_merchant_id")>0)card.addView(bodyText("流水 #"+item.optInt("id")+(item.optInt("mall_order_id")>0?" · 订单 #"+item.optInt("mall_order_id"):"")+(item.optInt("mall_merchant_id")>0?" · 店铺 #"+item.optInt("mall_merchant_id"):""),12,MUTED));
                  String txHash = normalizeOptionalText(item.optString("tx_hash", ""));
                 card.addView(bodyText(t("transaction_hash", item.optString("created_at", ""), txHash.isEmpty() ? t("none") : txHash), 11, MUTED));
                if ("withdraw".equals(type) && status.equals("pending")) {
                    TextView cancel = bodyText(t("cancel_withdraw"), 13, Color.rgb(190, 55, 55));
                    cancel.setPadding(0, dp(8), 0, 0);
                    cancel.setOnClickListener(v -> {
                        JSONObject request = new JSONObject();
                        try { request.put("transaction_id", item.optInt("id")); } catch (Exception ignored) { return; }
                        ApiClient.post(MainActivity.this, "wallet.withdraw.cancel", request, new ApiClient.Callback() {
                            @Override public void onSuccess(Object result) { toast(t("withdraw_cancelled")); openWalletTransactionsPage(symbol); }
                            @Override public void onError(String message) { toast(message); }
                        });
                    });
                    card.addView(cancel);
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.setMargins(0, 0, 0, dp(8));
                list.addView(card, params);
                shown++;
            }
            if (shown == 0) list.addView(note(t("no_transaction_records", t("all").equals(selected[0]) ? "" : selected[0])));
        };
        for (String label : labels) {
            TextView tab = centeredText(label, 14, label.equals(selected[0]) ? Color.WHITE : TEXT);
            tab.setBackground(round(label.equals(selected[0]) ? BLUE : Color.WHITE, 20));
            tab.setOnClickListener(v -> {
                selected[0] = label;
                for (int i = 0; i < filters.getChildCount(); i++) {
                    TextView button = (TextView) filters.getChildAt(i);
                    boolean active = labels[i].equals(label);
                    button.setBackground(round(active ? BLUE : Color.WHITE, 20));
                    button.setTextColor(active ? Color.WHITE : TEXT);
                }
                render[0].run();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1);
            p.setMargins(dp(4), 0, dp(4), 0);
            filters.addView(tab, p);
        }
        root.addView(filters);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
        ApiClient.get(this, "wallet.transactions", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (data instanceof JSONArray) transactions[0] = (JSONArray) data;
                render[0].run();
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private int walletTransactionDirection(String type) {
        if("red_packet_receive".equals(type)||"red_packet_refund".equals(type))return 1;
        if("red_packet_send".equals(type))return -1;
        if ("merchant_deposit_return".equals(type) || "merchant_settlement".equals(type) || "deposit".equals(type) || "refund".equals(type) || "staking_redeem".equals(type) || "staking_interest".equals(type) || "staking_dividend".equals(type) || "referral_reward".equals(type) || "exchange_in".equals(type)) return 1;
        if ("merchant_deposit".equals(type) || "withdraw".equals(type) || "staking".equals(type) || "redeem".equals(type) || "exchange_out".equals(type)) return -1;
        return 0;
    }

    private String displayAssetSymbol(String symbol) {
        return "SPX001".equals(symbol) ? t("points") : symbol;
    }

    private String walletTransactionLabel(String type) {
        if("red_packet_send".equals(type))return "发出红包";
        if("red_packet_receive".equals(type))return "领取红包";
        if("red_packet_refund".equals(type))return "红包过期退回";
        if ("merchant_deposit".equals(type)) return "店铺保证金冻结";
        if ("merchant_deposit_return".equals(type)) return "店铺保证金退还";
        if ("merchant_settlement".equals(type)) return "商城订单结算";
        if ("deposit".equals(type)) return t("deposit");
        if ("withdraw".equals(type)) return t("withdraw");
        if ("staking".equals(type)) return t("staking");
        if ("staking_redeem".equals(type)) return t("staking_redeem");
        if ("staking_interest".equals(type)) return t("staking_interest");
        if ("staking_dividend".equals(type)) return t("staking_dividend");
        if ("referral_reward".equals(type)) return t("referral_reward");
        if ("refund".equals(type)) return t("refund");
        if ("redeem".equals(type)) return t("redeem");
        if ("exchange_in".equals(type)) return t("exchange_in");
        if ("exchange_out".equals(type)) return t("exchange_out");
        if ("withdraw_refund".equals(type)) return t("withdraw_refund");
        if ("admin_adjustment".equals(type)) return t("admin_adjustment");
        return normalizeOptionalText(type).isEmpty() ? t("other") : type;
    }

    private String walletTransactionStatusLabel(String status) {
        if ("pending".equals(status)) return t("pending");
        if ("processing".equals(status)) return t("processing");
        // "sending" is the window between broadcast and 12 confirmations; without this the list
        // falls through to the raw English status.
        if ("sending".equals(status)) return t("sending");
        if ("completed".equals(status)) return t("completed");
        if ("failed".equals(status)) return t("failed");
        if ("rejected".equals(status)) return t("rejected");
        if ("cancelled".equals(status)) return t("cancelled");
        return normalizeOptionalText(status).isEmpty() ? t("unknown_status") : status;
    }

    private void addWalletAction(LinearLayout parent, String label, int color, Runnable action) {
        TextView button = centeredText(label, 14, Color.WHITE);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(round(color, 12));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(button, params);
    }

    private String walletBindingKey() { return accountCacheKey("wallet_bsc_binding"); }

    private void showBindBscWalletDialog() {
        String existing = normalizeOptionalText(preferences.getString(walletBindingKey(), ""));
        if (!existing.isEmpty()) { toast(t("wallet_bound_immutable")); return; }
        EditText address = formInput(t("wallet_address"), "");
        EditText password = formInput(t("wallet_password"), "");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout content = column();
        content.setPadding(dp(6), 0, dp(6), 0);
        content.addView(bodyText(t("wallet_binding_warning"), 13, Color.rgb(190, 75, 70)));
        content.addView(address);
        content.addView(password);
        new AlertDialog.Builder(this).setTitle(t("binding_wallet_title")).setView(content).setNegativeButton(t("cancel"), null).setPositiveButton(t("confirm_bind"), (dialog, which) -> {
            String value = address.getText().toString().trim();
            if (!value.matches("(?i)^0x[0-9a-f]{40}$")) { toast(t("wallet_address_invalid")); return; }
            String currentPassword = password.getText().toString();
            if (currentPassword.isEmpty()) { toast(t("wallet_password_required")); return; }
            new AlertDialog.Builder(this).setMessage(t("binding_confirm"))
                    .setNegativeButton(t("return_check"), null).setPositiveButton(t("permanent_bind"), (confirm, selected) -> {
                        JSONObject body = new JSONObject();
                        try { body.put("address", value); body.put("password", currentPassword); } catch (Exception ignored) { return; }
                        ApiClient.post(this, "wallet.bind", body, new ApiClient.Callback() {
                            @Override public void onSuccess(Object data) {
                                String bound = data instanceof JSONObject ? normalizeOptionalText(((JSONObject) data).optString("bound_address", "")) : "";
                                if (bound.isEmpty()) { toast(t("wallet_bind_unconfirmed")); return; }
                                preferences.edit().putString(walletBindingKey(), bound).apply();
                                toast(t("wallet_bound_immutable"));
                                openWalletPage();
                            }
                            @Override public void onError(String message) { toast(message); }
                        });
                    }).show();
        }).show();
    }

    private void addToken(LinearLayout parent, String symbol, String amount, String value, String quote, String stakeableAmount) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(Color.WHITE, 16));
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        boolean isSpx = symbol.startsWith("SPX");
        if ("XKRS".equals(symbol)) {
            ImageView fissIcon = new ImageView(this);
            fissIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fissIcon.setContentDescription("XKRS");
            fissIcon.setImageResource(com.spx.chat.R.mipmap.ic_fiss);
            heading.addView(fissIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        } else {
        ImageView coin = new ImageView(this);
        coin.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coin.setContentDescription(isSpx ? t("spx_icon_description") : t("usdt_icon_description"));
        coin.setImageResource(isSpx ? com.spx.chat.R.drawable.wallet_spx001 : com.spx.chat.R.drawable.wallet_usdt);
        heading.addView(coin, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        LinearLayout info = column();
        info.setPadding(dp(12), 0, 0, 0);
        String displaySymbol = displayAssetSymbol(symbol);
        TextView symbolView = bodyText(displaySymbol, 17, TEXT);
        symbolView.setTypeface(null, android.graphics.Typeface.BOLD);
        symbolView.setSingleLine(true);
        symbolView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(symbolView);
        if (isSpx || "XKRS".equals(symbol)) info.addView(bodyText(t("stakeable_balance_short_asset", formatAmount(stakeableAmount), displayAssetSymbol(symbol)), 12, Color.rgb(38, 115, 91)));
        info.addView(bodyText(quote + " · BSC", 12, MUTED));
        heading.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout balance = row();
        balance.setGravity(Gravity.CENTER_VERTICAL);
        balance.setPadding(0, dp(12), 0, 0);
        TextView amountView = bodyText(amount, 22, TEXT);
        amountView.setTypeface(null, android.graphics.Typeface.BOLD);
        amountView.setSingleLine(true);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(amountView, 12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        balance.addView(amountView, new LinearLayout.LayoutParams(0, dp(34), 1.5f));
        TextView valueView = bodyText(value, 13, MUTED);
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueView.setPadding(dp(8), 0, 0, 0);
        valueView.setSingleLine(true);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(valueView, 10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        balance.addView(valueView, new LinearLayout.LayoutParams(0, dp(34), 1));
        card.addView(balance, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(10));
        parent.addView(card, params);
        card.setOnClickListener(v -> openWalletTransactionsPage(symbol));
        card.setContentDescription(t("wallet_asset_description", displaySymbol, amount, value, quote));
    }

    private void showExchangeDialog() {
        LinearLayout form = column();
        form.setPadding(dp(4), dp(6), dp(4), dp(4));
        LinearLayout pair = row();
        TextView from = centeredText(t("exchange_from"), 16, TEXT);
        from.setTypeface(null, android.graphics.Typeface.BOLD);
        from.setBackground(round(Color.rgb(243, 246, 248), 12));
        TextView arrow = centeredText("→", 22, MUTED);
        TextView to = centeredText(t("exchange_to"), 16, Color.rgb(37, 132, 102));
        to.setTypeface(null, android.graphics.Typeface.BOLD);
        to.setBackground(round(Color.rgb(232, 246, 239), 12));
        pair.addView(from, new LinearLayout.LayoutParams(0, dp(62), 1));
        pair.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(54)));
        pair.addView(to, new LinearLayout.LayoutParams(0, dp(62), 1));
        form.addView(pair);

        TextView priceInfo = bodyText(t("exchange_quote_loading"), 13, MUTED);
        priceInfo.setPadding(0, dp(12), 0, dp(8));
        form.addView(priceInfo);
        EditText amount = formInput(t("exchange_usdt_amount"), "");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(amount);
        LinearLayout availableRow = row();
        String[] availableAmount = {"0"};
        TextView available = bodyText(t("available_balance_loading"), 12, MUTED);
        availableRow.addView(available, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView max = bodyText(t("all"), 12, BLUE);
        max.setOnClickListener(v -> { amount.setText(availableAmount[0]); amount.setSelection(amount.length()); });
        availableRow.addView(max);
        form.addView(availableRow);
        TextView estimate = bodyText(t("exchange_estimate_empty"), 17, TEXT);
        estimate.setTypeface(null, android.graphics.Typeface.BOLD);
        estimate.setPadding(0, dp(10), 0, dp(2));
        form.addView(estimate);
        TextView feeHint = bodyText(t("exchange_fee_included"), 12, MUTED);
        form.addView(feeHint);

        String[] spxPerUsdt = {""};
        boolean[] quoteReady = {false};
        Runnable updateEstimate = () -> {
            String value = amount.getText().toString().trim();
            if (!quoteReady[0] || value.isEmpty()) { estimate.setText(t("exchange_estimate_empty")); return; }
            try {
                java.math.BigDecimal result = new java.math.BigDecimal(value).multiply(new java.math.BigDecimal(spxPerUsdt[0])).setScale(8, java.math.RoundingMode.DOWN);
                estimate.setText(t("exchange_estimate", formatAmount(result.toPlainString())));
            } catch (Exception ignored) { estimate.setText(t("exchange_estimate_empty")); }
        };
        amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateEstimate.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        ApiClient.get(this, "wallet.exchange.quote", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) { priceInfo.setText(t("exchange_quote_unavailable")); return; }
                JSONObject quote = (JSONObject) data;
                String marketPrice = quote.optString("api_price_usd", "");
                String exchangePrice = quote.optString("exchange_price_usd", "");
                spxPerUsdt[0] = quote.optString("spx_per_usdt", "");
                String feeRate = quote.optString("fee_rate", "0.01");
                try {
                    quoteReady[0] = new java.math.BigDecimal(marketPrice).signum() > 0
                            && new java.math.BigDecimal(exchangePrice).signum() > 0
                            && new java.math.BigDecimal(spxPerUsdt[0]).signum() > 0;
                    String feePercent = formatAmount(new java.math.BigDecimal(feeRate).multiply(new java.math.BigDecimal("100")).toPlainString());
                    priceInfo.setText(t("exchange_quote_details", formatPrice(marketPrice), feePercent, formatPrice(exchangePrice), formatAmount(spxPerUsdt[0])));
                    feeHint.setText(t("exchange_fee_validation_hint"));
                } catch (Exception ignored) { quoteReady[0] = false; priceInfo.setText(t("exchange_quote_invalid")); }
                updateEstimate.run();
            }
            @Override public void onError(String message) { priceInfo.setText(t("exchange_quote_failed", message)); }
        });
        ApiClient.get(this, "wallet", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) { available.setText(t("wallet_balance_unavailable")); return; }
                JSONArray assets = (JSONArray) data;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && "USDT".equals(asset.optString("symbol"))) {
                        availableAmount[0] = asset.optString("balance", "0");
                        available.setText(t("available_balance", formatAmount(availableAmount[0]), "USDT"));
                        return;
                    }
                }
                available.setText(t("available_balance", "0.00", "USDT"));
            }
            @Override public void onError(String message) { available.setText(t("wallet_balance_unavailable")); }
        });

        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        showWalletTransferSheet(t("exchange_title"), t("confirm_exchange"), formScroll, (dialog,submitButton) -> {
            if (!quoteReady[0]) { toast(t("exchange_quote_not_ready")); return; }
            String rawAmount = amount.getText().toString().trim();
            java.math.BigDecimal input;
            try { input = new java.math.BigDecimal(rawAmount); }
            catch (Exception ignored) { toast(t("exchange_usdt_amount_invalid")); return; }
            if (input.signum() <= 0) { toast(t("exchange_amount_positive")); return; }
            if (input.compareTo(new java.math.BigDecimal(availableAmount[0])) > 0) { toast(t("exchange_insufficient_usdt")); return; }
            JSONObject request = new JSONObject();
            try {
                request.put("usdt_amount", input.stripTrailingZeros().toPlainString());
                request.put("request_id", UUID.randomUUID().toString());
            } catch (Exception ignored) { return; }
            ApiClient.post(this, "wallet.exchange", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    dialog.dismiss();
                    String received = data instanceof JSONObject ? ((JSONObject) data).optString("spx_amount", "") : "";
                    toast(t("exchange_success", received));
                    openWalletPage();
                }
                @Override public void onError(String message) { toast(message); }
            });
        });
    }

    private void showTransferDialog(String action) {
        showTransferDialog(action, null);
    }

    private void showTransferDialog(String action, String initialAsset) {
        if("withdraw".equals(action)&&recoverPendingFinancial("wallet.withdraw"))return;
        ApiClient.get(this, "wallet.binding", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                String boundAddress = data instanceof JSONObject ? normalizeOptionalText(((JSONObject) data).optString("bound_address", "")) : "";
                preferences.edit().putString(walletBindingKey(), boundAddress).apply();
                if (boundAddress.isEmpty()) {
                    toast(t("bind_wallet_first"));
                    showBindBscWalletDialog();
                    return;
                }
                showTransferDialog(action, initialAsset, boundAddress);
            }
            @Override public void onError(String message) { toast(t("wallet_binding_load_failed", message)); }
        });
    }

    private void showTransferDialog(String action, String initialAsset, String boundAddress) {
        if (financialDialogBlocked()) return;
        stakeDialogOpenedAt = android.os.SystemClock.elapsedRealtime();
        LinearLayout form = column();
        form.setPadding(dp(14), dp(6), dp(14), dp(8));
        ScrollView formScroll = new ScrollView(this);
        formScroll.setFillViewport(false);
        formScroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        TextView chain = bodyText(t("wallet_network"), 14, MUTED);
        chain.setPadding(0, dp(8), 0, dp(8));
        form.addView(chain);
        if (action.equals("deposit")) {
            String[] selectedAsset = {initialAsset == null ? "SPX001" : initialAsset};
            final Runnable[] refreshDepositAddress = new Runnable[1];
            TextView assetPicker = bodyText(t("deposit_asset", displayAssetSymbol(selectedAsset[0])), 15, BLUE);
            assetPicker.setPadding(dp(12), dp(10), dp(12), dp(10));
            assetPicker.setBackground(stroke(Color.WHITE, Color.rgb(211, 222, 241), 10, 1));
            assetPicker.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(t("select_deposit_asset")).setItems(new String[]{"USDT", t("points"), "XKRS"}, (dialog, which) -> {
                selectedAsset[0] = which == 0 ? "USDT" : (which == 1 ? "SPX001" : "XKRS");
                assetPicker.setText(t("deposit_asset", displayAssetSymbol(selectedAsset[0])));
                if (refreshDepositAddress[0] != null) refreshDepositAddress[0].run();
            }).show());
            form.addView(assetPicker);
            TextView sourceAddress = bodyText(t("deposit_source_address", boundAddress), 13, TEXT);
            sourceAddress.setPadding(dp(12), dp(10), dp(12), dp(10));
            sourceAddress.setBackground(round(Color.rgb(245, 247, 250), 10));
            form.addView(sourceAddress);
            TextView platformAddress = bodyText(t("deposit_address_loading"), 14, TEXT);
            platformAddress.setPadding(dp(14), dp(12), dp(14), dp(12));
            platformAddress.setBackground(round(Color.rgb(245, 247, 250), 12));
            form.addView(platformAddress);
            final JSONObject[] depositInfo = {null};
            refreshDepositAddress[0] = () -> {
                JSONObject info = depositInfo[0];
                if (info == null) { platformAddress.setText(t("deposit_address_loading")); return; }
                String addressKey = "USDT".equals(selectedAsset[0]) ? "usdt_address"
                        : ("XKRS".equals(selectedAsset[0]) ? "fiss_address" : "spx001_address");
                String address = info.optString(addressKey, info.optString("spx001_address", ""));
                platformAddress.setText(address.isEmpty() ? t("deposit_address_unconfigured") : address);
            };
            TextView copy = centeredText(t("copy_deposit_address"), 14, BLUE);
            copy.setOnClickListener(v -> {
                String address = platformAddress.getText().toString();
                if (address.contains("0x")) {
                    ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    manager.setPrimaryClip(ClipData.newPlainText(t("deposit_address"), address));
                    toast(t("deposit_address_copied"));
                } else toast(t("deposit_address_unconfigured"));
            });
            form.addView(copy, new LinearLayout.LayoutParams(-1, dp(44)));
            EditText hash = formInput(t("transfer_hash"), "");
            form.addView(hash);
            EditText amount = formInput(t("deposit_amount"), "");
            amount.setInputType(2 | 8192);
            form.addView(amount);
            ApiClient.get(this, "wallet.deposit.info", null, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (data instanceof JSONObject) {
                        depositInfo[0] = (JSONObject) data;
                        if (refreshDepositAddress[0] != null) refreshDepositAddress[0].run();
                    }
                }
                @Override public void onError(String message) { platformAddress.setText(t("deposit_address_unconfigured")); }
            });
            showWalletTransferSheet(t("deposit"), t("submit_deposit"), formScroll, (depositDialog,submitButton) -> {
                String address = platformAddress.getText().toString();
                String txHash = hash.getText().toString().trim();
                String value = amount.getText().toString().trim();
                if (!address.startsWith("0x")) { toast(t("deposit_address_unconfigured")); return; }
                if (txHash.isEmpty() || value.isEmpty()) { toast(t("deposit_fields_required")); return; }
                JSONObject body = new JSONObject();
                try { body.put("symbol", selectedAsset[0]); body.put("chain", "BSC"); body.put("amount", value); body.put("tx_hash", txHash); } catch (Exception ignored) { }
                ApiClient.post(this, "wallet.deposit", body, new ApiClient.Callback() {
                    @Override public void onSuccess(Object data) { depositDialog.dismiss(); toast(t("deposit_submitted")); }
                    @Override public void onError(String message) { toast(message); }
                });
            });
            return;
        }
        String[] selectedAsset = {initialAsset == null ? "SPX001" : initialAsset};
        String[] availableBalance = {"0"};
        Runnable[] refreshBalance = {null};
        TextView[] withdrawEstimate = {null};
        TextView assetPicker = bodyText(t("withdraw_asset", displayAssetSymbol(selectedAsset[0])), 15, BLUE);
        assetPicker.setPadding(dp(12), dp(10), dp(12), dp(10));
        assetPicker.setBackground(stroke(Color.WHITE, Color.rgb(211, 222, 241), 10, 1));
        assetPicker.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(t("select_withdraw_asset")).setItems(new String[]{"USDT", t("points"), "XKRS"}, (dialog, which) -> {
            selectedAsset[0] = which == 0 ? "USDT" : (which == 1 ? "SPX001" : "XKRS");
            assetPicker.setText(t("withdraw_asset", displayAssetSymbol(selectedAsset[0])));
            if (withdrawEstimate[0] != null) withdrawEstimate[0].setText(t("withdraw_estimate_empty", displayAssetSymbol(selectedAsset[0])));
            if (refreshBalance[0] != null) refreshBalance[0].run();
        }).show());
        form.addView(assetPicker);
        TextView address = bodyText(t("withdraw_address", boundAddress), 13, TEXT);
        address.setPadding(dp(12), dp(12), dp(12), dp(12));
        address.setBackground(round(Color.rgb(245, 247, 250), 10));
        address.setOnClickListener(v -> copyText(boundAddress, t("bound_withdraw_address")));
        form.addView(address);
        TextView assetTip = bodyText(t("withdraw_bound_address_hint"), 12, MUTED);
        form.addView(assetTip);
        EditText amount = formInput(t("withdraw_amount"), "");
        amount.setInputType(2 | 8192);
        form.addView(amount);
        LinearLayout balanceRow = row();
        TextView balanceHint = bodyText(t("available_balance_loading"), 12, MUTED);
        balanceRow.addView(balanceHint, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView allButton = centeredText(t("all"), 13, BLUE);
        allButton.setOnClickListener(v -> amount.setText(availableBalance[0]));
        balanceRow.addView(allButton, new LinearLayout.LayoutParams(dp(52), dp(38)));
        form.addView(balanceRow);
        TextView actualAmountHint = bodyText(t("withdraw_estimate_empty", displayAssetSymbol(selectedAsset[0])), 13, Color.rgb(38, 115, 91));
        withdrawEstimate[0] = actualAmountHint;
        actualAmountHint.setPadding(dp(12), dp(10), dp(12), dp(10));
        actualAmountHint.setBackground(round(Color.rgb(237, 248, 243), 10));
        form.addView(actualAmountHint);
        final java.util.HashMap<String, String> feeRates = new java.util.HashMap<>();
        feeRates.put("USDT", "1"); feeRates.put("SPX001", "1"); feeRates.put("XKRS", "1");
        final java.util.HashMap<String, String> minAmounts = new java.util.HashMap<>();
        minAmounts.put("USDT", "10"); minAmounts.put("SPX001", "1000"); minAmounts.put("XKRS", "10000");
        Runnable updateWithdrawEstimate = () -> {
            try {
                java.math.BigDecimal requested = new java.math.BigDecimal(amount.getText().toString().trim());
                String ratePercent = feeRates.containsKey(selectedAsset[0]) ? feeRates.get(selectedAsset[0]) : "1";
                java.math.BigDecimal fee = requested.multiply(new java.math.BigDecimal(ratePercent))
                        .divide(new java.math.BigDecimal("100"), 18, java.math.RoundingMode.DOWN);
                java.math.BigDecimal net = requested.subtract(fee);
                actualAmountHint.setText(t("withdraw_estimate", formatAmount(requested.toPlainString()), formatAmount(fee.toPlainString()), formatAmount(net.toPlainString()), displayAssetSymbol(selectedAsset[0])));
            } catch (Exception ignored) {
                actualAmountHint.setText(t("withdraw_estimate_empty", displayAssetSymbol(selectedAsset[0])));
            }
        };
        amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateWithdrawEstimate.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        updateWithdrawEstimate.run();
        ApiClient.get(this, "wallet.withdraw.config", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) return;
                JSONObject symbols = ((JSONObject) data).optJSONObject("symbols");
                if (symbols == null) return;
                for (String key : new String[]{"USDT", "SPX001", "XKRS"}) {
                    JSONObject cfg = symbols.optJSONObject(key);
                    if (cfg == null) continue;
                    feeRates.put(key, cfg.optString("fee_rate", "1"));
                    minAmounts.put(key, cfg.optString("min_amount", "0"));
                }
                updateWithdrawEstimate.run();
            }
            @Override public void onError(String message) { }
        });
        refreshBalance[0] = () -> ApiClient.get(this, "wallet", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                availableBalance[0] = "0";
                if (data instanceof JSONArray) for (int i = 0; i < ((JSONArray) data).length(); i++) {
                    JSONObject asset = ((JSONArray) data).optJSONObject(i);
                    if (asset != null && selectedAsset[0].equals(asset.optString("symbol"))) {
                        availableBalance[0] = asset.optString("balance", "0");
                        break;
                    }
                }
                balanceHint.setText(t("available_balance", formatAmount(availableBalance[0]), displayAssetSymbol(selectedAsset[0])));
            }
            @Override public void onError(String message) { balanceHint.setText(t("wallet_balance_load_failed")); }
        });
        refreshBalance[0].run();
        showWalletTransferSheet(t("withdraw"), t("submit_withdraw"), formScroll, (withdrawDialog,submitButton) -> {
            String walletAddress = boundAddress;
            String value = amount.getText().toString().trim();
            if (walletAddress.isEmpty() || value.isEmpty()) { toast(t("withdraw_fields_required")); return; }
            JSONObject body = new JSONObject();
            try { body.put("symbol", selectedAsset[0]); body.put("chain", "BSC"); body.put("amount", value); body.put("address", walletAddress); } catch (Exception ignored) { }
            try {
                java.math.BigDecimal requested = new java.math.BigDecimal(value);
                if (requested.signum() <= 0) { toast(t("withdraw_fields_required")); return; }
                String minRaw = minAmounts.get(selectedAsset[0]);
                if (minRaw != null && !minRaw.isEmpty()) {
                    java.math.BigDecimal minValue = new java.math.BigDecimal(minRaw);
                    if (minValue.signum() > 0 && requested.compareTo(minValue) < 0) {
                        toast("最小提现数量为 " + minRaw + " " + displayAssetSymbol(selectedAsset[0]));
                        return;
                    }
                }
            } catch (NumberFormatException invalid) { toast(t("withdraw_fields_required")); return; }
            submitFinancial("wallet.withdraw",body,withdrawDialog,submitButton);
        });
    }

    private interface WalletTransferSubmit { void submit(Dialog dialog,TextView button); }

    private void showWalletTransferSheet(String title, String actionLabel, View form, WalletTransferSubmit submit) {
        Dialog dialog = new Dialog(this);
        final ApiClient.SessionStamp formSession=new ApiClient.SessionStamp(this);
        trackAccountDialog(dialog);
        LinearLayout sheet = column();
        sheet.setPadding(dp(20), dp(16), dp(20), dp(18));
        sheet.setBackground(round(Color.WHITE, 20));
        LinearLayout heading = row();
        LinearLayout titleBlock = column();
        TextView titleView = bodyText(title, 19, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBlock.addView(titleView);
        titleBlock.addView(bodyText(t("wallet_transfer_sheet_hint"), 12, MUTED));
        heading.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = centeredText("×", 25, MUTED);
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(38), dp(42)));
        sheet.addView(heading);
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(237, 240, 243));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(12), 0, dp(8));
        sheet.addView(divider, dividerParams);
        sheet.addView(form, new LinearLayout.LayoutParams(-1, -2));
        TextView submitButton = centeredText(actionLabel, 15, Color.WHITE);
        submitButton.setTypeface(null, android.graphics.Typeface.BOLD);
        submitButton.setBackground(round(BLUE, 12));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(50));
        buttonParams.setMargins(0, dp(12), 0, 0);
        sheet.addView(submitButton, buttonParams);
        submitButton.setOnClickListener(v -> {if(formSession.valid(this))submit.submit(dialog,submitButton);else dialog.dismiss();});
        dialog.setContentView(sheet);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(getResources().getDisplayMetrics().widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.35f;
            window.setAttributes(attributes);
        }
    }

    private void showAddressDialog() {
        showTransferDialog("deposit");
    }

    private void openOrdersPage() { commerce().orders(); }

    private void openAddressPage() { commerce().addresses(null); }

    private EditText formInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(tr(hint));
        input.setText(value);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(105, 119, 143));
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        input.setBackground(inputBackground(false));
        input.setOnFocusChangeListener((view, focused) -> input.setBackground(inputBackground(focused)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(56));
        params.setMargins(0, 0, 0, dp(12));
        input.setLayoutParams(params);
        return input;
    }

    private GradientDrawable inputBackground(boolean focused) {
        GradientDrawable background = round(focused ? Color.WHITE : Color.rgb(246, 248, 253), 12);
        background.setStroke(dp(focused ? 2 : 1), focused ? BLUE : Color.rgb(183, 195, 218));
        return background;
    }

    private NumberPicker picker(String[] values, String selected) {
        if (values.length == 0) values = new String[]{t("please_select")};
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(values.length - 1);
        picker.setDisplayedValues(values);
        picker.setWrapSelectorWheel(true);
        picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        picker.setFocusable(true);
        picker.setFocusableInTouchMode(true);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) picker.setValue(i);
        }
        return picker;
    }

    private void addOrder(LinearLayout parent, String name, String price, String status) {
        addOrder(parent, 0, name, price, status);
    }

    private void addOrder(LinearLayout parent, int orderId, String name, String price, String status) {
        LinearLayout card = row();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(round(Color.WHITE, 16));
        LinearLayout info = column();
        info.addView(bodyText(name, 16, TEXT));
        info.addView(bodyText(t("order_number_value", orderId, price), 13, MUTED));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        String shownStatus = "pending".equals(status) ? t("order_pending") : ("shipped".equals(status) ? t("order_shipped") : ("delivered".equals(status) || "completed".equals(status) ? t("completed") : ("cancelled".equals(status) ? t("cancelled") : status)));
        card.addView(bodyText(shownStatus, 13, ("completed".equals(status) || "delivered".equals(status)) ? Color.rgb(46, 145, 95) : Color.rgb(196, 119, 33)));
        if (orderId > 0 && "pending".equals(status)) {
            TextView cancel = bodyText(t("cancel"), 13, Color.rgb(231, 76, 60));
            cancel.setPadding(dp(8), dp(4), 0, dp(4));
            cancel.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(t("cancel_order_confirm")).setNegativeButton(t("back"), null).setPositiveButton(t("cancel_order"), (d, w) -> {
                JSONObject request = new JSONObject();
                try { request.put("order_id", orderId); } catch (Exception ignored) { return; }
                ApiClient.post(this, "orders.cancel", request, new ApiClient.Callback() {
                    @Override public void onSuccess(Object data) { toast(t("order_cancelled")); openOrdersPage(); }
                    @Override public void onError(String message) { toast(message); }
                });
            }).show());
            card.addView(cancel);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(12));
        parent.addView(card, params);
    }

    private void openAddFriendPage() {
        LinearLayout root = subPageRoot(t("add_friend"), t("add_friend_subtitle"));
        LinearLayout body = column();
        body.setPadding(dp(20), dp(24), dp(20), dp(20));
        EditText input = formInput(t("email_or_spx_id"), "");
        input.setSingleLine(true);
        body.addView(input);
        TextView searchButton = centeredText(t("find_friend"), 15, Color.WHITE);
        searchButton.setTypeface(null, android.graphics.Typeface.BOLD);
        searchButton.setBackground(round(BLUE, 14));
        searchButton.setOnClickListener(v -> {
            String keyword = input.getText().toString().trim();
            if (keyword.isEmpty()) { toast(t("email_or_spx_id_required")); return; }
            Map<String, String> params = new HashMap<>();
            params.put("q", keyword);
            ApiClient.get(this, "contacts.search", params, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (!(data instanceof JSONArray) || ((JSONArray) data).length() == 0) { toast(t("user_not_found")); return; }
                    JSONObject user = ((JSONArray) data).optJSONObject(0);
                    if (user == null) { toast(t("user_not_found")); return; }
                    loadUserProfile(user.optInt("id"), new UserProfileCallback() {
                        @Override public void onProfile(JSONObject profile) { openFriendProfilePage(profile); }
                        @Override public void onError(String message) { toast(t("profile_load_failed", message)); }
                    });
                }
                @Override public void onError(String message) { toast(message); }
            });
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(48));
        buttonParams.setMargins(0, dp(14), 0, dp(24));
        body.addView(searchButton, buttonParams);
        body.addView(centeredText(t("scan_my_qr_hint"), 13, MUTED));
        TextView qr = centeredText(t("view_my_qr") + "\n" + currentUserSpxId, 20, BLUE);
        qr.setOnClickListener(v -> showQrDialog(currentUserSpxId));
        qr.setBackground(round(Color.WHITE, 18));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-1, dp(150));
        qrParams.setMargins(dp(30), dp(18), dp(30), 0);
        body.addView(qr, qrParams);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, activeTab);
    }

    private void openCreateGroupPage() {
        LinearLayout root = subPageRoot(t("group_create"), t("group_create_subtitle"));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(dp(16), dp(16), dp(16), dp(24));
        EditText groupName = formInput(t("group_name"), "");
        list.addView(groupName);
        Set<Integer> selectedIds = new HashSet<>();
        JSONArray[] friendList = {new JSONArray()};
        TextView selectedSummary = bodyText(t("no_friend_selected"), 14, MUTED);
        selectedSummary.setPadding(dp(14), dp(12), dp(14), dp(12));
        selectedSummary.setBackground(round(Color.WHITE, 10));
        list.addView(selectedSummary);
        TextView chooseFriends = centeredText(t("choose_members"), 14, BLUE);
        chooseFriends.setBackground(round(Color.rgb(238, 242, 255), 10));
        chooseFriends.setOnClickListener(v -> {
            if (friendList[0].length() == 0) { toast(t("friends_loading_or_empty")); return; }
            showFriendPicker(t("choose_group_members"), t("confirm_selection"), friendList[0], selected -> {
            selectedIds.clear();
            StringBuilder names = new StringBuilder();
            int count = 0;
            for (int i = 0; i < selected.length(); i++) {
                JSONObject contact = selected.optJSONObject(i);
                if (contact == null) continue;
                selectedIds.add(contact.optInt("id"));
                String remark = normalizeOptionalText(contact.optString("remark", ""));
                String nickname = normalizeOptionalText(contact.optString("nickname", ""));
                if (count > 0) names.append(t("list_separator"));
                names.append(remark.isEmpty() ? (nickname.isEmpty() ? t("friend") : nickname) : remark);
                count++;
            }
            selectedSummary.setText(count == 0 ? t("no_friend_selected") : t("selected_members", count, names));
            });
        });
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(48));
        chooseParams.setMargins(0, 0, 0, dp(12));
        list.addView(chooseFriends, chooseParams);
        ApiClient.get(this, "contacts", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) { if (data instanceof JSONArray) friendList[0] = (JSONArray) data; }
            @Override public void onError(String message) { list.addView(note(message)); }
        });
        TextView create = centeredText(t("create_group"), 15, Color.WHITE);
        create.setBackground(round(BLUE, 14));
        create.setOnClickListener(v -> {
            String name = groupName.getText().toString().trim();
            if (name.isEmpty()) { toast(t("group_name_required")); return; }
            if (selectedIds.isEmpty()) { toast(t("select_one_member")); return; }
            JSONArray memberIds = new JSONArray();
            for (Integer id : selectedIds) memberIds.put(id);
            JSONObject request = new JSONObject();
            try { request.put("name", name); request.put("member_ids", memberIds); } catch (Exception ignored) { return; }
            ApiClient.post(this, "groups.create", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    int groupId = data instanceof JSONObject ? ((JSONObject) data).optInt("group_id") : 0;
                    invalidateChatSnapshot();
                    closeSubPage();
                    toast(t("group_created"));
                    if (groupId > 0) openChatWindow(0, groupId, name, t("group_avatar"), "group", "#5B7CFA", "#9B5DE5");
                }
                @Override public void onError(String message) { toast(message); }
            });
        });
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(-1, dp(48));
        createParams.setMargins(0, dp(18), 0, 0);
        list.addView(create, createParams);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, activeTab);
    }

    private void openScanPage() {
        LinearLayout root = subPageRoot(t("scan"), t("scan_subtitle"));
        LinearLayout body = column();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(24), dp(48), dp(24), dp(24));
        TextView scan = centeredText("▦\n\n" + t("scan_subtitle"), 20, BLUE);
        scan.setBackground(round(Color.WHITE, 22));
        body.addView(scan, new LinearLayout.LayoutParams(-1, dp(280)));
        TextView start = centeredText(t("start_scan"), 15, Color.WHITE);
        start.setBackground(round(BLUE, 12));
        start.setOnClickListener(v -> openQrScanner());
        body.addView(start, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView fromGallery = centeredText(t("scan_gallery"), 15, BLUE);
        fromGallery.setOnClickListener(v -> {
            ChatGallery.open(this,"image/*",PICK_SCAN_IMAGE);
        });
        body.addView(fromGallery, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView byId = centeredText(t("join_by_group_id"), 15, BLUE);
        byId.setOnClickListener(v -> showFindGroupDialog());
        body.addView(byId, new LinearLayout.LayoutParams(-1, dp(50)));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, activeTab);
    }

    private static final int QR_SCAN_REQUEST = 0x5A10;

    /** 实时扫码：持续对焦、连续解码，无需按快门 */
    private void openQrScanner() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, QR_SCAN_REQUEST);
            return;
        }
        startActivityForResult(new Intent(this, QrScanActivity.class), QR_SCAN_REQUEST);
    }


    // ==================== 录音浮层 ====================
    private void showVoiceOverlay() {
        if (voiceOverlay == null) {
            FrameLayout overlay = new FrameLayout(this);
            overlay.setBackgroundColor(0x33000000);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(22), dp(20), dp(22), dp(18));
            card.setBackground(round(0xE6222222, 20));

            ImageView mic = new ImageView(this);
            mic.setImageDrawable(new ChatControlIcon("voice", Color.WHITE));
            card.addView(mic, new LinearLayout.LayoutParams(dp(44), dp(44)));

            voiceWave = new VoiceWaveView(this);
            LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(dp(170), dp(46));
            waveParams.topMargin = dp(12);
            card.addView(voiceWave, waveParams);

            voiceOverlayHint = centeredText("松开发送 · 上滑取消", 13, Color.WHITE);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-2, -2);
            hintParams.topMargin = dp(10);
            card.addView(voiceOverlayHint, hintParams);

            voiceOverlayTimer = centeredText("00:00", 12, 0xCCFFFFFF);
            card.addView(voiceOverlayTimer, new LinearLayout.LayoutParams(-2, -2));

            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(230), -2);
            cardParams.gravity = Gravity.CENTER;
            overlay.addView(card, cardParams);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            ((android.view.ViewGroup) getWindow().getDecorView())
                .addView(overlay, new android.view.ViewGroup.LayoutParams(-1, -1));
            voiceOverlay = overlay;
        }
        voiceOverlayCancelled = false;
        if (voiceWave != null) { voiceWave.clearLevels(); voiceWave.setCancelled(false); }
        if (voiceOverlayHint != null) { voiceOverlayHint.setText("松开发送 · 上滑取消"); voiceOverlayHint.setTextColor(Color.WHITE); }
        if (voiceOverlayTimer != null) voiceOverlayTimer.setText("00:00");
        voiceOverlay.setVisibility(View.VISIBLE);
    }

    private void hideVoiceOverlay() {
        if (voiceOverlay == null) return;
        voiceOverlay.setVisibility(View.GONE);
        if (voiceWave != null) { voiceWave.clearLevels(); voiceWave.setCancelled(false); }
        voiceOverlayCancelled = false;
    }

    private void updateVoiceOverlayTimer(long seconds) {
        if (voiceOverlayTimer == null || voiceOverlay == null || voiceOverlay.getVisibility() != View.VISIBLE) return;
        voiceOverlayTimer.setText(String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
    }

    private void updateVoiceOverlayCancel() {
        if (voiceOverlayHint == null) return;
        if (voiceOverlayCancelled) {
            voiceOverlayHint.setText("松开手指，取消发送");
            voiceOverlayHint.setTextColor(Color.rgb(255, 110, 110));
        } else {
            voiceOverlayHint.setText("松开发送 · 上滑取消");
            voiceOverlayHint.setTextColor(Color.WHITE);
        }
    }

    private void vibrate(int millis) {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(millis, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(millis);
            }
        } catch (Exception ignored) { }
    }

    // ==================== 聊天多选 ====================
    private void enterSelectionMode(JSONObject initial) {
        if (activeMessageList == null) return;
        selectingMessages = true;
        selectedMessageIds.clear();
        if (initial != null) selectedMessageIds.add(initial.optInt("id"));
        showSelectionBar();
        decorateAllSelectionRows();
        updateSelectionBar();
    }

    private void decorateAllSelectionRows() {
        for (int i = 0; i < activeMessageList.getChildCount(); i++) {
            View child = activeMessageList.getChildAt(i);
            if (child.getTag() instanceof Integer) decorateSelectionBlock(child);
        }
    }

    private void decorateSelectionBlock(View block) {
        if (block == null || !(block.getTag() instanceof Integer)) return;
        ViewGroup parent = (ViewGroup) block.getParent();
        if (parent == null) return;
        if (parent.getTag() != null && "sel-wrap".equals(parent.getTag())) {
            updateSelectionDot((ViewGroup) parent);
            return;
        }
        int index = parent.indexOfChild(block);
        ViewGroup.LayoutParams blockParams = block.getLayoutParams();
        parent.removeViewAt(index);

        FrameLayout wrap = new FrameLayout(this);
        wrap.setTag("sel-wrap");
        wrap.setPadding(dp(38), 0, 0, 0);
        wrap.addView(block, new FrameLayout.LayoutParams(-1, -2));

        TextView dot = new TextView(this);
        dot.setTag("sel-dot");
        dot.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(24), dp(24));
        dotParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        dotParams.leftMargin = dp(6);
        wrap.addView(dot, dotParams);

        View catcher = new View(this);
        catcher.setTag("sel-catcher");
        catcher.setOnClickListener(v -> toggleSelection((Integer) block.getTag()));
        wrap.addView(catcher, new FrameLayout.LayoutParams(-1, -1));

        parent.addView(wrap, index, blockParams);
        updateSelectionDot(wrap);
    }

    private void updateSelectionDot(ViewGroup wrap) {
        if (wrap == null) return;
        View dotView = wrap.findViewWithTag("sel-dot");
        if (!(dotView instanceof TextView)) return;
        TextView dot = (TextView) dotView;
        int id = 0;
        for (int i = 0; i < wrap.getChildCount(); i++) {
            View child = wrap.getChildAt(i);
            if (child.getTag() instanceof Integer) { id = (Integer) child.getTag(); break; }
        }
        boolean selected = selectedMessageIds.contains(id);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (selected) {
            shape.setColor(BLUE);
            dot.setText("✓");
            dot.setTextColor(Color.WHITE);
            dot.setTextSize(12);
        } else {
            shape.setColor(0x00FFFFFF);
            shape.setStroke(dp(1), 0xFFB6BCC6);
            dot.setText("");
        }
        dot.setBackground(shape);
    }

    private void toggleSelection(int id) {
        if (id <= 0) return;
        if (!selectedMessageIds.remove(id)) selectedMessageIds.add(id);
        refreshSelectionDots();
        updateSelectionBar();
    }

    private void refreshSelectionDots() {
        if (activeMessageList == null) return;
        for (int i = 0; i < activeMessageList.getChildCount(); i++) {
            View child = activeMessageList.getChildAt(i);
            if (child.getTag() != null && "sel-wrap".equals(child.getTag()) && child instanceof ViewGroup)
                updateSelectionDot((ViewGroup) child);
        }
    }

    private void showSelectionBar() {
        if (selectionBar != null) { selectionBar.setVisibility(View.VISIBLE); return; }
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(0xF7FFFFFF);

        TextView cancel = bodyText("取消", 16, TEXT);
        cancel.setPadding(dp(10), dp(14), dp(10), dp(14));
        cancel.setOnClickListener(v -> exitSelectionMode());
        bar.addView(cancel, new LinearLayout.LayoutParams(-2, -2));

        selectionCountLabel = centeredText("已选 0 条", 15, TEXT);
        bar.addView(selectionCountLabel, new LinearLayout.LayoutParams(0, -2, 1));

        TextView selectAll = bodyText("全选", 16, BLUE);
        selectAll.setPadding(dp(12), dp(14), dp(12), dp(14));
        selectAll.setOnClickListener(v -> {
            selectedMessageIds.clear();
            for (JSONObject m : activeMessageModels.values()) selectedMessageIds.add(m.optInt("id"));
            refreshSelectionDots();
            updateSelectionBar();
        });
        bar.addView(selectAll, new LinearLayout.LayoutParams(-2, -2));

        TextView forward = bodyText("转发", 16, BLUE);
        forward.setPadding(dp(12), dp(14), dp(12), dp(14));
        forward.setOnClickListener(v -> forwardSelectedMessages());
        bar.addView(forward, new LinearLayout.LayoutParams(-2, -2));

        TextView delete = bodyText("删除", 16, Color.rgb(214, 58, 58));
        delete.setPadding(dp(12), dp(14), dp(12), dp(14));
        delete.setOnClickListener(v -> deleteSelectedMessages());
        bar.addView(delete, new LinearLayout.LayoutParams(-2, -2));

        int statusBarHeight = 0;
        int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (statusBarId > 0) statusBarHeight = getResources().getDimensionPixelSize(statusBarId);
        bar.setPadding(dp(12), dp(8) + statusBarHeight, dp(12), dp(8));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(64) + statusBarHeight);
        params.gravity = Gravity.TOP;
        ((android.view.ViewGroup) getWindow().getDecorView()).addView(bar, params);
        selectionBar = bar;
    }

    private void updateSelectionBar() {
        if (selectionCountLabel != null) selectionCountLabel.setText("已选 " + selectedMessageIds.size() + " 条");
    }

    private void exitSelectionMode() {
        selectingMessages = false;
        selectedMessageIds.clear();
        if (selectionBar != null) selectionBar.setVisibility(View.GONE);
        if (activeMessageList == null) return;
        for (int i = activeMessageList.getChildCount() - 1; i >= 0; i--) {
            View child = activeMessageList.getChildAt(i);
            if (child.getTag() == null || !"sel-wrap".equals(child.getTag()) || !(child instanceof ViewGroup)) continue;
            FrameLayout wrap = (FrameLayout) child;
            View block = null;
            for (int j = 0; j < wrap.getChildCount(); j++) {
                View inner = wrap.getChildAt(j);
                if (inner.getTag() instanceof Integer) { block = inner; break; }
            }
            if (block == null) continue;
            android.view.ViewGroup.LayoutParams params = block.getLayoutParams();
            wrap.removeView(block);
            activeMessageList.removeViewAt(i);
            activeMessageList.addView(block, i, params);
        }
    }

    private void deleteSelectedMessages() {
        if (selectedMessageIds.isEmpty()) { toast("请选择要删除的消息"); return; }
        ArrayList<Integer> ids = new ArrayList<>(selectedMessageIds);
        if (ids.size() > 200) { toast("一次最多删除 200 条"); return; }
        final boolean group = activeGroupId > 0;
        final int conversation = group ? activeGroupId : activeChatId;
        suppressDeleteSync = true;
        for (int id : ids) {
            String key = (group ? "g" : "c") + id;
            JSONObject model = activeMessageModels.get(key);
            if (model != null) hideLocalMessage(model, renderedMessageViews.get(key));
        }
        suppressDeleteSync = false;
        exitSelectionMode();
        try {
            JSONObject body = new JSONObject();
            if (group) body.put("group_id", conversation); else body.put("chat_id", conversation);
            body.put("message_ids", new JSONArray(ids));
            ApiClient.post(this, group ? "groups.messages.delete.local" : "messages.delete.local", body, new ApiClient.Callback() {
                public void onSuccess(Object data) { }
                public void onError(String error) { toast(error); }
            });
        } catch (Exception ignored) { }
        toast("已删除 " + ids.size() + " 条记录（仅自己）");
    }

    private void forwardSelectedMessages() {
        if (selectedMessageIds.isEmpty()) { toast("请选择要转发的消息"); return; }
        JSONArray sources = new JSONArray();
        for (int id : selectedMessageIds) {
            JSONObject model = activeMessageModels.get((activeGroupId > 0 ? "g" : "c") + id);
            if (model != null) sources.put(model);
        }
        exitSelectionMode();
        if (sources.length() == 0) { toast("没有可转发的消息"); return; }
        chatTools().forwardMessages(sources);
    }

    private void openBenefitsPage() {
        LinearLayout root = subPageRoot(t("my_benefits"), t("benefits_subtitle"));
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(16));
        addBenefit(body, t("eco_pass"), t("eco_pass_desc"), t("activated"));
        addBenefit(body, t("staking_member"), t("staking_member_desc"), t("activated"));
        addBenefit(body, t("store_points"), t("store_points_desc"), t("redeemable"));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void addBenefit(LinearLayout parent, String title, String desc, String status) {
        LinearLayout item = row();
        item.setPadding(dp(16), dp(16), dp(16), dp(16));
        item.setBackground(round(Color.WHITE, 16));
        LinearLayout text = column();
        text.addView(bodyText(title, 17, TEXT));
        text.addView(bodyText(desc, 13, MUTED));
        item.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(bodyText(status, 13, BLUE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(12));
        parent.addView(item, params);
    }

    private void openHelpPage() {
        LinearLayout root = subPageRoot(t("support"), t("support_subtitle"));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        EditText subject = formInput(t("subject"), "");
        EditText detail = formInput(t("issue_description"), "");
        detail.setMinLines(4);
        detail.setGravity(Gravity.TOP | Gravity.START);
        body.addView(subject);
        body.addView(detail);
        TextView submit = centeredText(t("submit_ticket"), 15, Color.WHITE);
        submit.setBackground(round(BLUE, 12));
        submit.setOnClickListener(v -> {
            String title = subject.getText().toString().trim();
            String content = detail.getText().toString().trim();
            if (title.isEmpty() || content.isEmpty()) { toast(t("ticket_fields_required")); return; }
            JSONObject request = new JSONObject();
            try { request.put("subject", title); request.put("content", content); } catch (Exception ignored) { return; }
            ApiClient.post(this, "support.tickets.create", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    subject.setText("");
                    detail.setText("");
                    toast(t("ticket_submitted"));
                    loadSupportTickets(body);
                }
                @Override public void onError(String message) { toast(message); }
            });
        });
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(-1, dp(48));
        submitParams.setMargins(0, 0, 0, dp(16));
        body.addView(submit, submitParams);
        body.addView(section(t("my_submissions")));
        loadSupportTickets(body);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void openInvitePage() {
        LinearLayout root = subPageRoot(t("invite"), t("invite_subtitle"));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        LinearLayout inviteCard = column();
        inviteCard.setPadding(dp(20), dp(20), dp(20), dp(18));
        inviteCard.setBackground(gradient("#315BEB", "#4778F2", 18));
        inviteCard.addView(bodyText(t("invite_friends"), 18, Color.WHITE));
        TextView inviteSubtitle = bodyText(t("invite_reward_desc"), 13, Color.WHITE);
        inviteSubtitle.setPadding(0, dp(4), 0, dp(16));
        inviteCard.addView(inviteSubtitle);
        inviteCard.addView(bodyText(t("my_spx_id"), 12, Color.rgb(226, 235, 255)));
        TextView inviteId = bodyText(currentUserSpxId, 26, Color.WHITE);
        inviteId.setTypeface(null, android.graphics.Typeface.BOLD);
        inviteId.setPadding(0, dp(4), 0, dp(10));
        inviteId.setOnClickListener(v -> copyText(currentUserSpxId, "SPX ID"));
        inviteCard.addView(inviteId);
        inviteCard.addView(bodyText(t("invite_reward_rule"), 13, Color.WHITE));
        String[] inviteLink = {ApiClient.INVITE_BASE + "/login.php?invite=" + currentUserSpxId};
        TextView copyLink = centeredText(t("copy_invite_link"), 14, BLUE);
        copyLink.setPadding(0, dp(10), 0, dp(10));
        copyLink.setBackground(round(Color.WHITE, 10));
        copyLink.setOnClickListener(v -> copyText(inviteLink[0], t("invite_link")));
        inviteCard.addView(copyLink, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView qr = centeredText(t("show_invite_qr"), 14, BLUE);
        qr.setPadding(0, dp(10), 0, dp(10));
        qr.setBackground(round(Color.WHITE, 10));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-1, dp(42));
        qrParams.setMargins(0, dp(14), 0, 0);
        qr.setOnClickListener(v -> showQrDialog(inviteLink[0]));
        inviteCard.addView(qr, qrParams);
        body.addView(inviteCard);

        LinearLayout summary = row();
        summary.setPadding(0, dp(12), 0, dp(8));
        LinearLayout countTile = column();
        countTile.setPadding(dp(14), dp(12), dp(14), dp(12));
        countTile.setBackground(round(Color.WHITE, 12));
        countTile.addView(bodyText(t("invite_count"), 12, MUTED));
        TextView inviteCount = bodyText("—", 22, TEXT);
        inviteCount.setTypeface(null, android.graphics.Typeface.BOLD);
        countTile.addView(inviteCount);
        LinearLayout rewardTile = column();
        rewardTile.setPadding(dp(14), dp(12), dp(14), dp(12));
        rewardTile.setBackground(round(Color.WHITE, 12));
        rewardTile.addView(bodyText(t("total_reward"), 12, MUTED));
        TextView rewardTotal = bodyText("—", 22, BLUE);
        rewardTotal.setTypeface(null, android.graphics.Typeface.BOLD);
        rewardTile.addView(rewardTotal);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
        tileParams.setMargins(0, 0, dp(6), 0);
        summary.addView(countTile, tileParams);
        LinearLayout.LayoutParams rewardParams = new LinearLayout.LayoutParams(0, -2, 1);
        rewardParams.setMargins(dp(6), 0, 0, 0);
        summary.addView(rewardTile, rewardParams);
        body.addView(summary);
        LinearLayout withdrawRow = row();
        withdrawRow.setPadding(dp(14), dp(10), dp(12), dp(10));
        withdrawRow.setBackground(round(Color.WHITE, 12));
        LinearLayout availableInfo = column();
        availableInfo.addView(bodyText(t("withdrawable"), 12, MUTED));
        TextView available = bodyText(t("points_placeholder"), 16, TEXT);
        available.setTypeface(null, android.graphics.Typeface.BOLD);
        availableInfo.addView(available);
        withdrawRow.addView(availableInfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView withdraw = centeredText(t("go_withdraw"), 14, BLUE);
        withdraw.setOnClickListener(v -> showTransferDialog(t("withdraw"), "SPX001"));
        withdraw.setBackground(round(Color.rgb(238, 242, 255), 10));
        withdrawRow.addView(withdraw, new LinearLayout.LayoutParams(dp(82), dp(40)));
        LinearLayout.LayoutParams withdrawParams = new LinearLayout.LayoutParams(-1, -2);
        withdrawParams.setMargins(0, 0, 0, dp(8));
        body.addView(withdrawRow, withdrawParams);
        body.addView(section(t("my_referrals")));
        TextView loading = note(t("invite_loading"));
        loading.setTag("invite_loading");
        body.addView(loading);
        ApiClient.get(this, "referrals", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) return;
                JSONObject result = (JSONObject) data;
                currentUserSpxId = result.optString("invite_code", currentUserSpxId);
                inviteLink[0] = result.optString("invite_url", ApiClient.INVITE_BASE + "/login.php?invite=" + currentUserSpxId);
                inviteId.setText(currentUserSpxId);
                inviteCount.setText(result.optString("invited_count", "0"));
                rewardTotal.setText(formatAmount(result.optString("total_rewards", "0")));
                    available.setText(t("points_amount", formatAmount(result.optString("withdrawable_spx", "0"))));
                for (int i = body.getChildCount() - 1; i >= 0; i--) {
                    if ("invite_loading".equals(body.getChildAt(i).getTag())) body.removeViewAt(i);
                }
                JSONArray users = result.optJSONArray("users");
                 if (users == null || users.length() == 0) { body.addView(note(t("no_referrals"))); return; }
                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.optJSONObject(i);
                    if (user == null) continue;
                    LinearLayout member = column();
                    member.setPadding(dp(14), dp(12), dp(14), dp(12));
                    member.setBackground(round(Color.WHITE, 10));
                    member.addView(bodyText(user.optString("nickname", t("spx_user")), 15, TEXT));
                    member.addView(bodyText(user.optString("spx_id", ""), 12, BLUE));
                     member.addView(bodyText(t("referral_summary", formatAmount(user.optString("stake_total", "0")), formatAmount(user.optString("reward_total", "0"))), 12, MUTED));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.setMargins(0, 0, 0, dp(8));
                    body.addView(member, params);
                }
            }
            @Override public void onError(String message) {
                for (int i = body.getChildCount() - 1; i >= 0; i--) {
                    if ("invite_loading".equals(body.getChildAt(i).getTag())) body.removeViewAt(i);
                }
                body.addView(note(message));
            }
        });
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void loadSupportTickets(LinearLayout body) {
        ApiClient.get(this, "support.tickets", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) return;
                for (int i = body.getChildCount() - 1; i >= 0; i--) {
                    if ("support_ticket".equals(body.getChildAt(i).getTag())) body.removeViewAt(i);
                }
                JSONArray tickets = (JSONArray) data;
                for (int i = 0; i < tickets.length(); i++) {
                    JSONObject ticket = tickets.optJSONObject(i);
                    if (ticket == null) continue;
                    LinearLayout item = column();
                    item.setTag("support_ticket");
                    item.setPadding(dp(14), dp(12), dp(14), dp(12));
                    item.setBackground(round(Color.WHITE, 10));
                    item.addView(bodyText(ticket.optString("subject"), 15, TEXT));
                    item.addView(bodyText(ticket.optString("content"), 13, MUTED));
                    String reply = ticket.optString("admin_reply", "");
                     item.addView(bodyText(t("ticket_status", ticket.optString("status"), reply), 12, BLUE));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.setMargins(0, 0, 0, dp(8));
                    body.addView(item, params);
                }
                 if (tickets.length() == 0) body.addView(note(t("no_submissions")));
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void openSecurityPage() {
        openPaymentPasswordSettings();
    }

    void openPaymentPasswordSettings() { PaymentPasswordSettings.open(this); }

    private void openSettingsPage() {
        LinearLayout root = subPageRoot(t("general_settings"), "");
        LinearLayout body = column();
        body.setPadding(0, dp(12), 0, dp(24));
        addSettingsSection(body, "支付安全", new String[]{"payment|支付密码|独立六位数字密码"});
        addSettingsSection(body, t("general"), new String[]{"notifications|消息通知与角标|", "language|" + t("language") + "|" + languageDisplayName(), "background|" + t("chat_background") + "|", "lock|" + t("change_password") + "|", "update|" + t("check_updates") + "|", "cache|" + t("clear_cache") + "|", "about|" + t("about") + "|"});
        addSettingsSection(body, "", new String[]{"history|" + t("update_history") + "|", "agreement|" + t("agreement") + "|"});
        TextView logout = centeredText(t("sign_out"), 16, Color.rgb(231, 76, 60));
        logout.setBackground(stroke(Color.WHITE, Color.rgb(255, 190, 190), 12, 1));
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(t("logout_confirm")).setNegativeButton(t("cancel"), null).setPositiveButton(t("sign_out"), (d, w) -> {
            if (ApiClient.token(this).isEmpty()) {
                ApiClient.clearToken(this);
                showAuthenticationPage();
                return;
            }
            ApiClient.post(this, "auth.logout", new JSONObject(), new ApiClient.Callback() {
                private void clearSession() {
                    ApiClient.clearToken(MainActivity.this);
                    chatHomePage = null; activeChatRows = null; activeChatRowsAdapter = null; activeChatSearchField = null;
                    chatHomeUserId = 0; chatSnapshotRevision++; renderedChatSnapshotSignature = "";
                    showAuthenticationPage();
                }
                @Override public void onSuccess(Object data) { clearSession(); }
                @Override public void onError(String message) { clearSession(); toast(t("local_login_cleared")); }
            });
        }).show());
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(-1, dp(58));
        logoutParams.setMargins(dp(20), dp(22), dp(20), 0);
        body.addView(logout, logoutParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void openMessageNotificationSettings() {
        LinearLayout root=subPageRoot("消息通知与角标","");
        LinearLayout box=column();box.setPadding(dp(20),dp(16),dp(20),dp(24));
        TextView status=bodyText(ChatBadge.permitted(this)?"系统通知已允许":"系统通知未允许，横幅和角标可能不可见",14,MUTED);box.addView(status);
        addNotificationToggle(box,"新消息系统通知","notifications_enabled");
        addNotificationToggle(box,"通知显示消息内容","notifications_preview");
        addNotificationToggle(box,"桌面未读角标","notifications_badge");
        TextView system=bodyText("打开系统通知设置  ›",16,BLUE);system.setPadding(0,dp(20),0,dp(20));system.setOnClickListener(v->{Intent intent=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());if(Build.VERSION.SDK_INT<26)intent=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));try{startActivity(intent);}catch(Exception e){toast("请在系统设置中管理通知权限");}});box.addView(system);
        if(Build.VERSION.SDK_INT>=33&&!ChatBadge.permitted(this)){TextView allow=bodyText("申请通知权限",16,BLUE);allow.setPadding(0,dp(12),0,dp(12));allow.setOnClickListener(v->requestChatNotificationPermission());box.addView(allow);}
        box.addView(bodyText("打开聊天并成功标记已读后自动更新角标。桌面显示数字或圆点由手机系统决定。尚未配置厂商推送，App 被结束后暂不保证收到新消息。",13,MUTED));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));openSubPage(root,3);
    }
    private void addNotificationToggle(LinearLayout box,String label,String key){
        Switch toggle=new Switch(this);toggle.setText(label);toggle.setTextSize(16);toggle.setTextColor(TEXT);toggle.setPadding(0,dp(16),0,dp(16));toggle.setChecked(preferences.getBoolean(key,true));toggle.setOnCheckedChangeListener((button,checked)->{preferences.edit().putBoolean(key,checked).apply();if(("notifications_enabled".equals(key)||"notifications_badge".equals(key)))ChatBadge.clear(this);ChatBadge.update(this,currentUserId,readChatSnapshot());});box.addView(toggle,new LinearLayout.LayoutParams(-1,-2));
    }

    private void addSettingsSection(LinearLayout parent, String title, String[] items) {
        LinearLayout section = column();
        section.setPadding(dp(12), dp(8), dp(12), dp(8));
        section.setBackground(MallUi.surface(this, Color.WHITE, 16, true));
        if (!title.isEmpty()) {
            TextView heading = bodyText(title, 14, MUTED);
            heading.setPadding(dp(4), dp(8), 0, dp(8));
            section.addView(heading);
        }
        for (int itemIndex = 0; itemIndex < items.length; itemIndex++) {
            String entry = items[itemIndex];
            String[] parts = entry.split("\\|", -1);
            String icon = parts[0];
            String name = parts[1];
            String value = parts.length > 2 ? parts[2] : "";
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), 0, dp(4), 0);
            row.setMinimumHeight(dp(60));
            MallUi.touch(row, 12);
            SimpleIconView iconView = new SimpleIconView(this, icon);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconParams.setMargins(0, 0, dp(10), 0);
            row.addView(iconView, iconParams);
            TextView label = bodyText(name, 16, TEXT);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (!value.isEmpty()) {
                TextView detail = bodyText(value, 13, MUTED);
                detail.setMaxLines(1);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detail.setMaxWidth(dp(112));
                detail.setPadding(dp(8), 0, dp(8), 0);
                row.addView(detail);
            }
            row.addView(bodyText("›", 22, Color.rgb(184, 197, 214)));
            row.setOnClickListener(v -> {
                   if ("notifications".equals(icon)) openMessageNotificationSettings();
                   else if ("payment".equals(icon)) openPaymentPasswordSettings();
                   else if (name.equals(t("language"))) showLanguageDialog();
                   else if (name.equals(t("chat_background"))) showChatBackgroundDialog();
                   else if (name.equals(t("change_password"))) showChangePasswordDialog();
                 else if (name.equals(t("check_updates"))) checkAppUpdate(true);
                 else if (name.equals(t("update_history"))) openUpdateHistoryPage();
                 else if (name.equals(t("about"))) showAboutDialog();
                 else if (name.equals(t("agreement"))) showAgreementDialog();
                 else if (name.equals(t("clear_cache"))) showMediaCacheDialog();
                 else toast(t("settings_action", name));
            });
            section.addView(row);
            if (itemIndex < items.length - 1) {
                View separator = new View(this);
                separator.setBackgroundColor(MallUi.LINE);
                LinearLayout.LayoutParams divider = new LinearLayout.LayoutParams(-1, dp(1));
                divider.setMargins(dp(52), 0, dp(4), 0);
                section.addView(separator, divider);
            }
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(16), dp(8), dp(16), dp(10));
        parent.addView(section, params);
    }


    private int installedVersionCode() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, code));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void checkAppUpdate(boolean manual) {
        if (updateCheckInProgress) { manualUpdateCheckPending |= manual; return; }
        updateCheckInProgress = true;
        Map<String, String> params = new HashMap<>();
        params.put("version_code", String.valueOf(installedVersionCode()));
        ApiClient.get(this, "app.update.check", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                updateCheckInProgress = false;
                boolean requested = manual || manualUpdateCheckPending;
                manualUpdateCheckPending = false;
                if (isFinishing() || isDestroyed()) return;
                if (!(data instanceof JSONObject)) { if (requested) toast(t("update_info_invalid")); return; }
                JSONObject result = (JSONObject) data;
                int version = result.optInt("version_code", 0);
                if (!result.optBoolean("update_available", false) || version <= installedVersionCode()) {
                    if (requested) toast(t("current_latest")); return;
                }
                if (availableRelease == null || availableRelease.optInt("version_code") != version) updatePromptShown = false;
                availableRelease = result;
                if (updateDownload.busy() || (updateDownload.stage == AppUpdateDownload.Stage.READY && updateDownload.matches(result))) {
                    if (requested) openAppUpdatePage(updateDownload.release);
                    return;
                }
                boolean skipped = preferences.getInt("skipped_update_version", -1) == version;
                if (requested) updatePromptShown = false;
                if (requested || (!skipped && !updatePromptShown)) showAppUpdatePrompt(result);
            }
            @Override public void onError(String message) {
                updateCheckInProgress = false;
                boolean requested = manual || manualUpdateCheckPending;
                manualUpdateCheckPending = false;
                if (requested && !isFinishing() && !isDestroyed()) toast(t("update_check_failed", message));
            }
        });
    }

    private void showAppUpdatePrompt(JSONObject release) {
        if (isFinishing() || isDestroyed() || updatePromptShown || (appUpdatePrompt != null && appUpdatePrompt.isShowing())) return;
        updatePromptShown = true;
        String version = release.optString("version_name", t("new_version"));
        String notes = release.optString("release_notes", t("update_optimization")).trim();
        String message = t("new_version_message", version, notes);
        appUpdatePrompt = new AlertDialog.Builder(this)
                .setTitle(t("new_version"))
                .setMessage(message + "\n\n" + t("update_skip_hint"))
                .setPositiveButton(t("send_and_install"), (dialog, which) -> openAppUpdatePage(release))
                .setNegativeButton(t("update_skip_version"), (dialog, which) -> skipAppUpdate(release))
                .setOnCancelListener(dialog -> skipAppUpdate(release))
                .setCancelable(true)
                .show();
    }

    private void skipAppUpdate(JSONObject release) {
        int version = release.optInt("version_code", 0);
        if (version > 0) preferences.edit().putInt("skipped_update_version", version).apply();
    }

    private boolean isAppUpdatePageVisible() { return updatePage != null && updatePage.isAttachedToWindow(); }

    private void openAppUpdatePage(JSONObject release) {
        if (release == null) return;
        if (isAppUpdatePageVisible()) { renderAppUpdateDownload(); return; }
        if (updateDownload.busy()) release = updateDownload.release;
        final JSONObject selectedRelease = release;
        LinearLayout root = subPageRoot(t("app_update"), "SPX IM · Android");
        root.setTag("app-update");
        updatePage = root; updatePageRelease = selectedRelease;
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(20), dp(20), dp(32));
        LinearLayout versionPanel = column();
        versionPanel.setPadding(dp(18), dp(18), dp(18), dp(18));
        versionPanel.setBackground(round(Color.WHITE, 16));
        TextView versionTitle = bodyText(t("app_version", release.optString("version_name", "")), 22, TEXT);
        versionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        versionPanel.addView(versionTitle);
        versionPanel.addView(bodyText(t("update_optional_hint"), 13, MUTED));
        body.addView(versionPanel);
        TextView notesHeading = bodyText(t("update_notes"), 16, TEXT);
        notesHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        notesHeading.setPadding(0, dp(24), 0, dp(10));
        body.addView(notesHeading);
        TextView notes = bodyText(release.optString("release_notes", t("update_optimization")), 14, Color.rgb(77, 91, 108));
        notes.setPadding(dp(16), dp(16), dp(16), dp(16)); notes.setBackground(round(Color.WHITE, 14));
        notes.setLineSpacing(dp(4), 1f); body.addView(notes);
        updatePageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updatePageProgress.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(8));
        progressParams.setMargins(0, dp(28), 0, dp(10)); body.addView(updatePageProgress, progressParams);
        updatePageStatus = bodyText(t("wait_download"), 13, MUTED); body.addView(updatePageStatus);
        updatePageButton = centeredText(t("send_and_install"), 16, Color.WHITE);
        updatePageButton.setTypeface(null, android.graphics.Typeface.BOLD); updatePageButton.setBackground(round(BLUE, 14));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(54));
        buttonParams.setMargins(0, dp(24), 0, 0); body.addView(updatePageButton, buttonParams);
        updatePageButton.setOnClickListener(v -> {
            if (updateDownload.matches(selectedRelease) && updateDownload.stage == AppUpdateDownload.Stage.READY && updateDownload.file != null && updateDownload.file.isFile()) installDownloadedApk(updateDownload.file);
            else updateDownload.start(selectedRelease);
        });
        TextView minimize = centeredText(t("update_minimize"), 15, BLUE);
        minimize.setPadding(0, dp(17), 0, dp(17)); body.addView(minimize, new LinearLayout.LayoutParams(-1, -2));
        minimize.setOnClickListener(v -> { leaveAppUpdatePage(); if (updateDownload.busy()) toast(t("update_minimized_hint")); });
        TextView skip = centeredText(t("update_skip_version"), 14, MUTED);
        skip.setPadding(0, dp(10), 0, dp(16)); body.addView(skip, new LinearLayout.LayoutParams(-1, -2));
        skip.setOnClickListener(v -> { skipAppUpdate(selectedRelease); leaveAppUpdatePage(); });
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (bottomNav == null) setContentView(root); else openSubPage(root, 3);
        root.post(this::renderAppUpdateDownload);
    }

    private void leaveAppUpdatePage() {
        if (bottomNav == null) showAuthenticationPage(); else closeSubPage();
        renderAppUpdateDownload();
    }

    private void renderAppUpdateDownload() {
        if (updateDownload == null || isFinishing() || isDestroyed()) return;
        boolean visible = isAppUpdatePageVisible();
        AppUpdateDownload.Stage stage = updateDownload.stage;
        String state;
        if (stage == AppUpdateDownload.Stage.DOWNLOADING) state = updateDownload.percent >= 0 ? t("download_percent", updateDownload.percent) : t("downloading_update_package");
        else if (stage == AppUpdateDownload.Stage.VERIFYING) state = t("download_complete_verifying");
        else if (stage == AppUpdateDownload.Stage.READY) state = t("apk_ready_to_install");
        else if (stage == AppUpdateDownload.Stage.ERROR) state = updateDownload.errorKey.isEmpty() ? updateDownload.error : t(updateDownload.errorKey);
        else state = t("wait_download");
        if (updateDownloadChip != null) {
            boolean show = stage != AppUpdateDownload.Stage.IDLE && !visible;
            updateDownloadChip.setVisibility(show ? View.VISIBLE : View.GONE);
            updateDownloadChip.setText(state + "  ›");
        }
        if (!visible || updatePageButton == null || updatePageProgress == null || updatePageStatus == null) return;
        boolean matching = updateDownload.matches(updatePageRelease);
        boolean busy = matching && updateDownload.busy();
        boolean ready = matching && stage == AppUpdateDownload.Stage.READY && updateDownload.file != null && updateDownload.file.isFile();
        updatePageProgress.setIndeterminate(busy && (stage == AppUpdateDownload.Stage.VERIFYING || updateDownload.percent < 0));
        updatePageProgress.setProgress(matching ? Math.max(0, updateDownload.percent) : 0);
        updatePageStatus.setText(matching ? state : t("wait_download"));
        updatePageButton.setEnabled(!busy);
        updatePageButton.setText(ready ? t("open_installer") : busy ? t("download") : matching && stage == AppUpdateDownload.Stage.ERROR ? t("redownload") : t("send_and_install"));
    }

    private void installDownloadedApk(File apk) {
        if (apk == null || !apk.isFile()) { toast(t("downloaded_apk_missing")); return; }
        pendingUpdateApk = apk;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = true;
            try {
                Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(permission);
            } catch (Exception exception) {
                waitingForInstallPermission = false;
                toast(t("allow_unknown_app_install"));
            }
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception exception) {
            toast(t("installer_open_failed", exception.getMessage() == null ? t("retry") : exception.getMessage()));
        }
    }

    private void openUpdateHistoryPage() {
        LinearLayout root = subPageRoot(t("update_history"), t("update_history_subtitle"));
        LinearLayout body = column();
        body.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView loading = note(t("reading_release_history"));
        body.addView(loading);
        ApiClient.get(this, "app.update.history", null, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                body.removeView(loading);
                if (!(data instanceof JSONArray) || ((JSONArray) data).length() == 0) {
                    body.addView(note(t("no_update_history")));
                    return;
                }
                JSONArray releases = (JSONArray) data;
                int installedCode = installedVersionCode();
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.optJSONObject(i);
                    if (release == null) continue;
                    LinearLayout card = column();
                    card.setPadding(dp(16), dp(14), dp(16), dp(14));
                    card.setBackground(round(Color.WHITE, 10));
                    TextView version = bodyText(t("release_version", release.optString("version_name"), release.optInt("version_code")), 16, TEXT);
                    version.setTypeface(null, android.graphics.Typeface.BOLD);
                    card.addView(version);
                    TextView published = bodyText(release.optString("created_at"), 12, MUTED);
                    published.setPadding(0, dp(3), 0, dp(8));
                    card.addView(published);
                    TextView notes = bodyText(release.optString("release_notes"), 14, Color.rgb(77, 91, 108));
                    notes.setLineSpacing(dp(3), 1f);
                    card.addView(notes);
                    TextView state = bodyText(release.optInt("version_code") == installedCode ? t("currently_installed") : t("published"), 12, BLUE);
                    state.setPadding(0, dp(8), 0, 0);
                    card.addView(state);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.setMargins(0, 0, 0, dp(10));
                    body.addView(card, params);
                }
            }
            @Override public void onError(String message) {
                body.removeView(loading);
                body.addView(note(t("release_history_failed", message)));
            }
        });
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void showAboutDialog() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            new AlertDialog.Builder(this).setTitle(t("about_spx_im")).setMessage(t("about_version", version)).setPositiveButton(t("ok"), null).show();
        } catch (Exception ignored) {
            toast(t("version_info_failed"));
        }
    }

    private void showLanguageDialog() {
        String[] values = {t("lang_simplified"), t("lang_traditional"), t("lang_english"), t("lang_japanese"), t("lang_korean")};
        String[] codes = {"zh-CN", "zh-TW", "en", "ja", "ko"};
        int selected = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(appLanguage)) selected = i;
        new AlertDialog.Builder(this).setTitle(t("language")).setSingleChoiceItems(values, selected, (dialog, which) -> {
            appLanguage = codes[which];
            preferences.edit().putString("language", appLanguage).apply();
            dialog.dismiss();
            refreshForLanguageChange();
            toast(t("language_saved"));
        }).setNegativeButton(t("cancel"), null).show();
    }

    /** Rebuild the active user surface so every app-owned label uses the new locale immediately. */
    private void refreshForLanguageChange() {
        chatWindowOpen = false;
        askWindowOpen = false;
        subPageOpen = false;
        walletAssetDetailOpen = false;
        if (bottomNav == null || content == null) {
            if (bottomNav == null) renderAuthenticationPage();
            return;
        }
        setBottomNavVisible(true);
        renderTab(activeTab);
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            TextView item = (TextView) bottomNav.getChildAt(i);
            item.setTextColor(i == activeTab ? BLUE : MUTED);
        }
    }

    private void showChangePasswordDialog() {
        LinearLayout form = column();
        form.setPadding(dp(8), 0, dp(8), 0);
        TextView email = bodyText(userEmail.isEmpty() ? t("reading_bound_email") : t("verification_sent_to", userEmail), 14, MUTED);
        email.setPadding(dp(0), dp(4), 0, dp(12));
        EditText code = formInput(t("email_code"), "");
        code.setInputType(2);
        TextView sendCode = centeredText(t("get_code"), 14, BLUE);
        sendCode.setBackground(round(Color.rgb(238, 242, 255), 12));
        EditText newPassword = formInput(t("new_password"), "");
        newPassword.setInputType(129);
        form.addView(email);
        LinearLayout codeRow = row();
        codeRow.addView(code, new LinearLayout.LayoutParams(0, dp(56), 1));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(112), dp(56));
        sendParams.setMargins(dp(8), 0, 0, 0);
        codeRow.addView(sendCode, sendParams);
        form.addView(codeRow);
        form.addView(newPassword);
        sendCode.setOnClickListener(v -> {
            sendCode.setEnabled(false);
            ApiClient.post(this, "password.email.code", new JSONObject(), new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (data instanceof JSONObject) {
                        userEmail = ((JSONObject) data).optString("email", userEmail);
                        ownProfile().edit().putString("user_email", userEmail).apply();
                        email.setText(t("verification_sent_to", userEmail));
                    }
                    startCodeCountdown(sendCode, 60);
                }
                @Override public void onError(String message) { sendCode.setEnabled(true); toast(message); }
            });
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t("change_password")).setView(form).setNegativeButton(t("cancel"), null).setPositiveButton(t("save"), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String verificationCode = code.getText().toString().trim();
            String newValue = newPassword.getText().toString();
            if (newValue.length() < 6) { toast(t("password_short")); return; }
            if (!verificationCode.matches("\\d{6}")) { toast(t("email_code_invalid")); return; }
            JSONObject body = new JSONObject();
            try { body.put("code", verificationCode); body.put("new_password", newValue); } catch (Exception ignored) { }
            ApiClient.post(this, "password.update.email", body, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (data instanceof JSONObject) ApiClient.saveToken(MainActivity.this, ((JSONObject) data).optString("token", ""));
                    dialog.dismiss();
                    toast(t("password_changed"));
                }
                @Override public void onError(String message) { toast(message); }
            });
        }));
        dialog.show();
    }

    private void showAgreementDialog() {
        new AlertDialog.Builder(this).setTitle(t("agreement")).setMessage(t("agreement_text"))
                .setPositiveButton(t("know"), null).show();
    }

    private long cacheBytes(File folder){File[] files=folder.listFiles();long total=0;if(files!=null)for(File file:files)total+=file.isDirectory()?cacheBytes(file):file.length();return total;}
    private void showMediaCacheDialog(){
        TextView info=bodyText("正在计算缓存…",14,TEXT);info.setPadding(dp(24),dp(16),dp(24),dp(16));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("清理图片、语音与视频缓存").setView(info).setNegativeButton(t("cancel"),null).setPositiveButton(t("clear"),null).create();
        dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);info.setText("正在清理…");ChatMediaWork.clearCaches(()->{ChatMediaFiles.clearUnused(new File(getFilesDir(),"media-cache"));ChatMediaFiles.clearUnused(new File(getFilesDir(),"ecology-image-cache"));info.post(()->{if(dialog.isShowing())dialog.dismiss();toast(t("cache_cleared"));});});});
            ChatMediaWork.execute(()->{long size=cacheBytes(new File(getFilesDir(),"media-cache"))+cacheBytes(new File(getFilesDir(),"ecology-image-cache"));info.post(()->{if(dialog.isShowing()&&dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled())info.setText(String.format(java.util.Locale.CHINA,"可清理 %.1f MB\n保留聊天记录、草稿、未发送附件和正在播放的媒体；查看历史媒体时会重新下载。",size/1048576.0));});});});dialog.show();
    }
    private void clearDirectory(java.io.File directory) {
        java.io.File[] files = directory.listFiles();
        if (files == null) return;
        for (java.io.File file : files) {
            if (file.isDirectory()) clearDirectory(file);
            file.delete();
        }
    }

    private void openSimpleSettingsPage(String title, String[] items) {
        LinearLayout root = subPageRoot(title, "");
        LinearLayout body = column();
        body.setPadding(0, dp(12), 0, dp(12));
        for (String itemName : items) {
            addProfileMenu(body, "*", itemName, "", () -> toast(t("open_demo", itemName)));
        }
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, 3);
    }

    private void showActionSheet() {
        LinearLayout box = dialogBox();
        TextView title = centeredText(t("new_item"), 18, Color.BLACK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title, new LinearLayout.LayoutParams(-1, dp(34)));
        addDialogAction(box, "▦", t("scan"), t("scan_join_group_desc"), () -> openScanPage());
        addDialogAction(box, "◎", t("join_group"), t("join_group_desc"), () -> showFindGroupDialog());
        addDialogAction(box, "+", t("add_friend_short"), t("add_friend_desc"), () -> openAddFriendPage());
        addDialogAction(box, "◎", t("group_create"), t("create_group_desc"), () -> openCreateGroupPage());
        addDialogAction(box,"◷","定时消息","管理待发送的消息",()->chatTools().schedules(0,0));
        addDialogAction(box,"↩","自动回复","管理私聊和群聊回复规则",()->chatTools().rules(0));
        TextView cancel = centeredText(t("cancel"), 16, Color.rgb(231, 76, 60));
        cancel.setPadding(0, dp(14), 0, dp(14));
        cancel.setOnClickListener(v -> ((Dialog) box.getTag()).dismiss());
        box.addView(cancel);
        showTopActionDialog(box);
    }

    private void showRemarkDialog(String defaultValue, TextView target, String remarkKey, int peerId, boolean contactLabel) {
        if (peerId <= 0) { toast(t("contact_unavailable_remark")); return; }
        EditText input = new EditText(this);
        input.setHint(t("remark_input"));
        String initialRemark = normalizeOptionalText(defaultValue);
        input.setText(t("none").equals(initialRemark) ? "" : initialRemark);
        new AlertDialog.Builder(this).setTitle(t("set_remark")).setView(input).setNegativeButton(t("cancel"), null).setPositiveButton(t("save"), (d, w) -> {
            String remark = input.getText().toString().trim();
            JSONObject body = new JSONObject();
            try { body.put("contact_user_id", peerId); body.put("remark", remark); } catch (Exception ignored) { return; }
            ApiClient.post(this, "contacts.remark", body, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    preferences.edit().putString(remarkKey, remark).apply();
                    handleContactRemarkUpdate(peerId, remark);
                    JSONObject cachedProfile = readCachedUserProfile(peerId);
                    if (cachedProfile != null) {
                        try { cachedProfile.put("contact_remark", remark); cachedProfile.put("is_contact", 1); }
                        catch (Exception ignored) { }
                        cacheUserProfile(cachedProfile);
                        preferences.edit().putLong(userProfileCacheKey(peerId) + "_time", System.currentTimeMillis()).apply();
                    }
                    target.setText(contactLabel ? t("remark_display", remark.isEmpty() ? t("none") : remark) : (remark.isEmpty() ? t("remark_not_set") : remark));
                    invalidateChatSnapshot();
                    if (activeTab == 0 && !chatWindowOpen && !subPageOpen) refreshVisibleChatList();
                    if (activeTab == 1 && !subPageOpen) renderTab(1);
                    toast(remark.isEmpty() ? t("remark_cleared") : t("remark_saved"));
                }
                @Override public void onError(String message) { toast(message); }
            });
        }).show();
    }

    private void openConversationDetail(String name, String avatarText, String status, boolean group, String c1, String c2) {
        saveActiveChatDraft();
        int detailChatId = activeChatId;
        int detailPeerId = activePeerUserId > 0 ? activePeerUserId : findChatPeerUserId(detailChatId);
        activePeerUserId = detailPeerId;
        LinearLayout root = subPageRoot(t("chat_details"), "");
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(0), dp(0), dp(0), dp(24));

        LinearLayout members = row();
        members.setPadding(dp(28), dp(24), dp(28), dp(20));
        members.setBackgroundColor(Color.WHITE);
        FrameLayout avatarFrame = new FrameLayout(this);
        avatarFrame.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(68)));
        TextView fallbackAvatar = avatar(avatarText, c1, c2, 68);
        avatarFrame.addView(fallbackAvatar, new FrameLayout.LayoutParams(-1, -1));
        ImageView profileAvatar = new ImageView(this);
        profileAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profileAvatar.setBackground(gradient(c1, c2, 50));
        profileAvatar.setClipToOutline(true);
        profileAvatar.setVisibility(View.GONE);
        avatarFrame.addView(profileAvatar, new FrameLayout.LayoutParams(-1, -1));
        members.addView(avatarFrame, new LinearLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout profileInfo = column();
        profileInfo.setPadding(dp(14), 0, 0, 0);
        TextView profileName = bodyText(normalizeOptionalText(name).isEmpty() ? t("contact") : normalizeOptionalText(name), 19, TEXT);
        profileName.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView profileId = bodyText(t("reading_profile"), 13, MUTED);
        profileInfo.addView(profileName);
        profileInfo.addView(profileId);
        members.addView(profileInfo, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(members);
        if (detailPeerId > 0) {
            loadUserProfile(detailPeerId, new UserProfileCallback() {
                @Override public void onProfile(JSONObject user) {
                    String nickname = normalizeOptionalText(user.optString("nickname", ""));
                    String remark = normalizeOptionalText(user.optString("contact_remark", ""));
                    String spxId = normalizeOptionalText(user.optString("spx_id", ""));
                    String avatarUrl = normalizeOptionalText(user.optString("avatar_url", ""));
                    profileName.setText(remark.isEmpty() ? (nickname.isEmpty() ? t("contact") : nickname) : remark);
                    profileId.setText(spxId.isEmpty() ? t("spx_user") : t("spx_id_label", spxId));
                    if (!avatarUrl.isEmpty()) loadAvatarInto(profileAvatar, avatarUrl);
                }
                @Override public void onError(String message) { profileId.setText(t("profile_unavailable")); }
            });
        } else {
            profileId.setText(t("profile_unavailable"));
        }

        LinearLayout find = settingRow("⌕", t("search_chat_content"), true);
        find.setOnClickListener(v -> openMessageSearchPage(detailChatId, 0, name));
        body.addView(find);
        LinearLayout muteRow = settingSwitchRow(t("mute_messages"), false);
        Switch muteSwitch = (Switch) muteRow.getChildAt(1);
        body.addView(muteRow);
        LinearLayout pinRow = settingSwitchRow(t("pin_chat"), false);
        Switch pinSwitch = (Switch) pinRow.getChildAt(1);
        body.addView(pinRow);
        loadChatSettings(detailChatId, muteSwitch, pinSwitch);
        LinearLayout clear = settingRow("⌫", t("clear_history"), false);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(t("clear_chat_confirm", name)).setNegativeButton(t("cancel"), null).setPositiveButton(t("clear_records"), (d, w) -> {
            if (detailChatId <= 0) { toast(t("conversation_unavailable")); return; }
            JSONObject request = new JSONObject();
            try { request.put("chat_id", detailChatId); } catch (Exception ignored) { return; }
            ApiClient.post(this, "messages.clear", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    chatCache.clear("chat", detailChatId);
                    renderedMessageIds.clear();
                    renderedMessageViews.clear();
                    activeMessageModels.clear();
                    invalidateChatSnapshot();
                    toast(t("chat_history_cleared"));
                    closeSubPage();
                    if(chatWindowOpen&&activeChatId==detailChatId)openChatWindow(detailChatId,0,name,avatarText,status,c1,c2,detailPeerId);
                }
                @Override public void onError(String message) { toast(message); }
            });
        }).show());
        body.addView(clear);
        LinearLayout complaint = settingRow("!", t("complaint"), true);
        complaint.setOnClickListener(v -> openHelpPage());
        body.addView(complaint);
        LinearLayout block = settingRow("⊘", t("block"), false);
        block.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(t("block_confirm")).setNegativeButton(t("cancel"), null).setPositiveButton(t("block"), (d, w) -> {
            if (detailChatId <= 0) { toast(t("conversation_unavailable")); return; }
            JSONObject request = new JSONObject();
            try { request.put("chat_id", detailChatId); request.put("blocked", 1); } catch (Exception ignored) { return; }
            ApiClient.post(this, "chats.settings", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) { toast(t("blocked")); closeSubPage(); }
                @Override public void onError(String message) { toast(message); }
            });
        }).show());
        body.addView(block);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        openSubPage(root, activeTab);
    }

    private void loadChatSettings(int chatId, Switch mute, Switch pinned) {
        if (chatId <= 0) return;
        Map<String, String> params = new HashMap<>();
        params.put("chat_id", String.valueOf(chatId));
        ApiClient.get(this, "chats.settings.get", params, new ApiClient.Callback() {
            private void bind() {
                mute.setOnCheckedChangeListener((button, checked) -> saveChatSetting(chatId, "mute", checked));
                pinned.setOnCheckedChangeListener((button, checked) -> {
                    saveChatSetting(chatId, "pinned", checked);
                    invalidateChatSnapshot();
                });
            }
            @Override public void onSuccess(Object data) {
                if (data instanceof JSONObject) {
                    JSONObject settings = (JSONObject) data;
                    mute.setChecked(settings.optInt("mute") == 1);
                    pinned.setChecked(settings.optInt("pinned") == 1);
                }
                bind();
            }
            @Override public void onError(String message) { bind(); }
        });
    }

    private void saveChatSetting(int chatId, String key, boolean value) {
        JSONObject body = new JSONObject();
        try { body.put("chat_id", chatId); body.put(key, value ? 1 : 0); } catch (Exception ignored) { return; }
        ApiClient.post(this, "chats.settings", body, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                JSONArray snapshot = readChatSnapshot();
                for (int i = 0; i < snapshot.length(); i++) {
                    JSONObject item = snapshot.optJSONObject(i);
                    if (item == null || "group".equals(item.optString("kind")) || item.optInt("id") != chatId) continue;
                    try { item.put(key, value ? 1 : 0); } catch (Exception ignored) { }
                }
                storeChatSnapshot(snapshot);
                invalidateChatSnapshot();
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private int cachedGroupSetting(int groupId, String key) {
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == groupId) return item.optInt(key);
        }
        return 0;
    }

    private void saveGroupSetting(int groupId, String key, boolean value) {
        JSONObject body = new JSONObject();
        try { body.put("group_id", groupId); body.put(key, value ? 1 : 0); } catch (Exception ignored) { return; }
        ApiClient.post(this, "groups.settings", body, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                JSONArray snapshot = readChatSnapshot();
                for (int i = 0; i < snapshot.length(); i++) {
                    JSONObject item = snapshot.optJSONObject(i);
                    if (item == null || !"group".equals(item.optString("kind")) || item.optInt("id") != groupId) continue;
                    try { item.put(key, value ? 1 : 0); } catch (Exception ignored) { }
                }
                storeChatSnapshot(snapshot);
                invalidateChatSnapshot();
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void openMessageSearchPage(int chatId, int groupId, String conversationName) {
        LinearLayout root = subPageRoot(t("search_chat_content"), conversationName);
        EditText query = search(t("search_message_input"));
        root.addView(query);
        ScrollView scroll = new ScrollView(this);
        LinearLayout results = column();
        results.setPadding(dp(16), dp(8), dp(16), dp(20));
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int[] generation = {0};
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] pending = {null};
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending[0] != null) handler.removeCallbacks(pending[0]);
                String keyword = s.toString().trim();
                int requestGeneration = ++generation[0];
                results.removeAllViews();
                if (keyword.isEmpty()) return;
                pending[0] = () -> {
                    Map<String, String> params = new HashMap<>();
                    params.put("q", keyword);
                    if (chatId > 0) params.put("chat_id", String.valueOf(chatId));
                    if (groupId > 0) params.put("group_id", String.valueOf(groupId));
                    ApiClient.get(MainActivity.this, "messages.search", params, new ApiClient.Callback() {
                        @Override public void onSuccess(Object data) {
                            if (requestGeneration != generation[0]) return;
                            if (!(data instanceof JSONArray) || ((JSONArray) data).length() == 0) { results.addView(note(t("no_matching_messages"))); return; }
                            JSONArray matches = (JSONArray) data;
                            for (int i = 0; i < matches.length(); i++) {
                                JSONObject item = matches.optJSONObject(i);
                                if (item == null) continue;
                                LinearLayout row = column();
                                row.setPadding(dp(14), dp(11), dp(14), dp(11));
                                row.setBackground(round(Color.WHITE, 10));
                                row.addView(bodyText(item.optString("conversation_name", conversationName), 13, BLUE));
                                row.addView(bodyText(item.optString("content", ""), 15, TEXT));
                                row.addView(bodyText(shortChatTime(item.optString("created_at", "")), 11, MUTED));
                                row.setOnClickListener(v -> {
                                    int resultChatId = item.optInt("chat_id");
                                    int resultGroupId = item.optInt("group_id");
                                    pendingSearchMessageId=item.optInt("id");
                                    openChatWindow(resultChatId, resultGroupId, item.optString("conversation_name", conversationName), resultGroupId > 0 ? t("group_avatar") : t("chat_avatar"), resultGroupId > 0 ? "group" : "online", resultGroupId > 0 ? "#5B7CFA" : "#A8C0FF", resultGroupId > 0 ? "#9B5DE5" : "#3F5EFB");
                                });
                                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                                p.setMargins(0, 0, 0, dp(8));
                                results.addView(row, p);
                            }
                        }
                        @Override public void onError(String message) { if (requestGeneration == generation[0]) results.addView(note(message)); }
                    });
                };
                handler.postDelayed(pending[0], 280);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        openSubPage(root, activeTab);
    }

    private void openGroupDetailPage(String name, String avatarText, String c1, String c2) {
        openGroupDetailPage(name, avatarText, c1, c2, true);
    }

    private String groupDetailCacheKey(int groupId) { return accountCacheKey("group_detail") + "_" + groupId; }

    private void cacheGroupDetail(JSONObject group) {
        if (group == null || group.optInt("id") <= 0) return;
        preferences.edit().putString(groupDetailCacheKey(group.optInt("id")), group.toString()).apply();
        JSONArray snapshot = readChatSnapshot();
        boolean found = false;
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item == null || !"group".equals(item.optString("kind")) || item.optInt("id") != group.optInt("id")) continue;
            try {
                JSONObject merged = new JSONObject(item.toString());
                java.util.Iterator<String> fields = group.keys();
                while (fields.hasNext()) {
                    String field = fields.next();
                    // Group metadata timestamps must not reorder conversations or replace message previews.
                    if ("updated_at".equals(field) || "last_message_at".equals(field) || field.startsWith("last_message")) continue;
                    merged.put(field, group.get(field));
                }
                merged.put("kind", "group");
                merged.put("unread_count", item.optInt("unread_count"));
                merged.put("mute", item.optInt("mute"));
                merged.put("pinned", item.optInt("pinned"));
                snapshot.put(i, merged);
                found = true;
            } catch (Exception ignored) { }
        }
        if (!found) {
            try { JSONObject merged = new JSONObject(group.toString()); merged.put("kind", "group"); snapshot.put(merged); } catch (Exception ignored) { }
        }
        storeChatSnapshot(snapshot);
        updateActiveGroupTitle();
    }

    private void openGroupDetailPage(String name, String avatarText, String c1, String c2, boolean refresh) {
        JSONObject groupInfo = null;
        try {
            String cached = preferences.getString(groupDetailCacheKey(activeGroupId), "");
            if (!cached.isEmpty()) groupInfo = new JSONObject(cached);
        } catch (Exception ignored) { }
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; groupInfo == null && i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == activeGroupId) { groupInfo = item; break; }
        }
        if (groupInfo != null) preferences.edit().putString(groupDetailCacheKey(activeGroupId), groupInfo.toString()).apply();
        if (groupInfo == null) {
            LinearLayout loading = subPageRoot(t("chat_details"), t("group_info_members"));
            loading.setTag("group:" + activeGroupId);
            loading.addView(note(t("reading_group_info")), new LinearLayout.LayoutParams(-1, dp(120)));
            openSubPage(loading, activeTab);
            Map<String, String> params = new HashMap<>();
            params.put("group_id", String.valueOf(activeGroupId));
            ApiClient.get(this, "groups.info", params, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (!(data instanceof JSONObject)) { toast(t("group_info_unavailable")); return; }
                    JSONObject group = (JSONObject) data;
                    cacheGroupDetail(group);
                    openGroupDetailPage(group.optString("name", name), avatarText, c1, c2, false);
                }
                @Override public void onError(String message) { toast(t("group_info_failed", message)); }
            });
            return;
        }
        String groupName = groupInfo.optString("name", name);
        String groupCode = normalizeOptionalText(groupInfo.optString("group_code", ""));
        String groupAvatarUrl = groupInfo.optString("avatar_url", "");
        String announcement = normalizeOptionalText(groupInfo.optString("announcement", ""));
        int memberCount = groupInfo.optInt("member_count");
        boolean isOwner = "owner".equals(groupInfo.optString("role"));
        boolean canManage = isOwner || "admin".equals(groupInfo.optString("role"));
        boolean canViewMembers = isOwner || "admin".equals(groupInfo.optString("role")) || groupInfo.optInt("allow_member_details", 1) == 1;
        activeGroupCanManage = canManage;
        activeGroupCanViewMemberDetails = canViewMembers;
        LinearLayout root = GroupUi.page(this, canManage ? t("group_management") : t("chat_details"), this::closeSubPage);
        root.setTag("group:" + activeGroupId);
        root.setBackgroundColor(MallUi.BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(8), dp(16), dp(24));
        activeGroupName = groupName;
        activeDetailGroupId = activeGroupId;
        final int detailGroupId = activeDetailGroupId;
        LinearLayout summary = row();
        summary.setPadding(dp(4), dp(10), dp(4), dp(22));
        String groupInitial = groupAvatarInitial(groupName);
        summary.addView(avatarWithRemoteImage(groupInitial, groupAvatarUrl, "#568AF2", "#2869EA", 52));
        LinearLayout groupInfoText = column();
        groupInfoText.setPadding(dp(12), 0, 0, 0);
        TextView nameView = bodyText(groupName, 18, MallUi.INK);
        nameView.setMaxLines(2);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        groupInfoText.addView(nameView);
        TextView membershipLabel = bodyText((memberCount > 0 ? memberCount + " 位成员" : t("group_members"))
                + (isOwner ? " · " + t("owner") : canManage ? " · " + t("administrator") : ""), 12, MallUi.SECONDARY);
        membershipLabel.setPadding(0, dp(5), 0, 0);
        groupInfoText.addView(membershipLabel);
        summary.addView(groupInfoText, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(summary);
        LinearLayout memberCard = GroupUi.card(this, body);
        LinearLayout memberHeading = row();
        memberHeading.setPadding(0, dp(10), 0, dp(14));
        TextView memberTitle = bodyText(t("group_members"), 15, MallUi.INK);
        memberTitle.setTypeface(null, 1);
        memberHeading.addView(memberTitle, new LinearLayout.LayoutParams(0, -2, 1));
        memberHeading.addView(bodyText(memberCount > 0 ? String.valueOf(memberCount) : "", 12, MallUi.SECONDARY));
        memberCard.addView(memberHeading);
        GroupUi.MemberGrid members = new GroupUi.MemberGrid(this);
        memberCard.addView(members, new LinearLayout.LayoutParams(-1, -2));
        TextView memberState = centeredText(canViewMembers ? t("reading_group_info") : t("member_details_disabled"), 12, MallUi.SECONDARY);
        memberState.setPadding(0, dp(16), 0, dp(16));
        memberCard.addView(memberState);
        TextView allMembers = centeredText(t("view_group_members") + "  ›", 13, MallUi.PRIMARY);
        allMembers.setTypeface(null, 1);
        allMembers.setMinimumHeight(dp(46));
        MallUi.touch(allMembers, 12);
        allMembers.setOnClickListener(v -> { if (canViewMembers) showGroupMembersDialog(); else toast(t("member_details_disabled")); });
        memberCard.addView(allMembers, new LinearLayout.LayoutParams(-1, -2));
        Map<String, String> memberParams = new HashMap<>();
        memberParams.put("group_id", String.valueOf(detailGroupId));
        memberParams.put("limit", "20");
        if (canViewMembers) ApiClient.get(this, "groups.members", memberParams, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray) || detailGroupId != activeDetailGroupId || root.getParent() != content) return;
                members.removeAllViews();
                JSONArray liveMembers = (JSONArray) data;
                memberState.setVisibility(liveMembers.length() == 0 ? View.VISIBLE : View.GONE);
        memberState.setText("暂无可展示的成员");
                for (int i = 0; i < Math.min(20, liveMembers.length()); i++) {
                    JSONObject member = liveMembers.optJSONObject(i);
                    if (member == null) continue;
                    String role = member.optString("role", "member");
                    addGroupMember(members, member.optInt("id"), chatDisplayName(member, t("group_member")), member.optString("avatar_url", ""), role, true);
                }
            }
            @Override public void onError(String message) {
                if (root.getParent() != content) return;
                memberState.setText("加载失败，点击重试");
                memberState.setTextColor(MallUi.PRIMARY);
                memberState.setOnClickListener(v -> openGroupDetailPage(name, avatarText, c1, c2, true));
            }
        });
        LinearLayout management = GroupUi.card(this, body);
        GroupUi.menu(this, management, "notice", t("group_announcement"), announcement.isEmpty() ? t("no_group_announcement") : announcement,
                () -> { if (canManage) editGroupField(t("group_announcement"), announcement);
                    else new AlertDialog.Builder(this).setTitle(t("group_announcement")).setMessage(announcement.isEmpty() ? t("no_group_announcement") : announcement).setPositiveButton(t("close"), null).show(); });
        GroupUi.menu(this, management, "people", canManage ? t("group_management") : t("group_members"),
                canManage ? "邀请 / 权限 / 成员" : t("view_group_members"),
                () -> { if (canViewMembers) showGroupMembersDialog(); else toast(t("member_details_disabled")); });
        if (canManage) GroupUi.menu(this, management, "bot", "群机器人", "触发词 / 自动回复", () -> chatTools().rules(detailGroupId));
        LinearLayout sharing = GroupUi.card(this, body);
        GroupUi.menu(this, sharing, "share", t("share_group"), t("share_group_desc"), this::showGroupShareDialog);
        GroupUi.menu(this, sharing, "grid", t("group_qr"), "邀请好友加入", () -> showGroupQrDialog(groupCode, groupName, groupAvatarUrl, memberCount));
        LinearLayout options = column();
        LinearLayout moreCard = GroupUi.card(this, body);
        View moreToggle = GroupUi.menu(this, moreCard, "grid", "更多设置", "群资料 / 消息 / 权限", () -> {});
        moreToggle.setOnClickListener(v -> {
            boolean expanded = options.getVisibility() != View.VISIBLE;
            options.setVisibility(expanded ? View.VISIBLE : View.GONE);
            View arrow = ((ViewGroup) moreToggle).getChildAt(((ViewGroup) moreToggle).getChildCount() - 1);
            arrow.animate().rotation(expanded ? 90 : 0).setDuration(160).start();
            moreToggle.setContentDescription("更多设置，" + (expanded ? "已展开，点击收起" : "已收起，点击展开"));
        });
        moreToggle.setContentDescription("更多设置，已收起，点击展开");
        moreCard.addView(options);
        options.setVisibility(View.GONE);
        GroupUi.section(this, options, "群资料");
        addGroupMenu(options, "order", t("group_id"), groupCode.isEmpty() ? t("group_code_loading") : groupCode, () -> {
            if (groupCode.isEmpty()) toast(t("group_code_unavailable")); else copyText(groupCode, t("group_id"));
        });
        addGroupMenu(options, "edit", t("group_name"), groupName, canManage ? () -> editGroupField(t("group_name"), groupName) : null);
        addGroupMenu(options, "image", t("group_avatar_label"), groupAvatarUrl.isEmpty() ? t("not_set") : t("set"), canManage ? this::chooseGroupAvatar : null);
        GroupUi.section(this, options, "成员管理");
        if (canManage) addGroupMenu(options, "people", t("invite_members"), t("choose_from_contacts"), this::showAddGroupMembersDialog);
        addGroupMenu(options, "search", t("search_group_members"), canViewMembers ? "群主 / 管理员 / 成员" : t("member_details_disabled"), () -> {
            if (canViewMembers) showGroupMembersDialog(); else toast(t("member_details_disabled"));
        });
        GroupUi.section(this, options, "消息设置");
        addGroupMenu(options, "search", t("search_chat_content"), "", () -> openMessageSearchPage(0, detailGroupId, groupName));
        int cachedMute = cachedGroupSetting(activeDetailGroupId, "mute");
        int cachedPinned = cachedGroupSetting(activeDetailGroupId, "pinned");
        LinearLayout groupMuteRow = GroupUi.switchRow(this, "bell", t("mute_messages"), cachedMute == 1);
        Switch groupMute = (Switch) groupMuteRow.getChildAt(1);
        groupMute.setOnCheckedChangeListener((button, checked) -> saveGroupSetting(activeDetailGroupId, "mute", checked));
        options.addView(groupMuteRow);
        LinearLayout groupPinRow = GroupUi.switchRow(this, "arrow", t("pin_chat"), cachedPinned == 1);
        Switch groupPin = (Switch) groupPinRow.getChildAt(1);
        groupPin.setOnCheckedChangeListener((button, checked) -> {
            saveGroupSetting(activeDetailGroupId, "pinned", checked);
            invalidateChatSnapshot();
        });
        options.addView(groupPinRow);
        if (canManage) {
            GroupUi.section(this, options, "群权限");
            addGroupSwitch(options, t("allow_member_details"), groupInfo.optInt("allow_member_details", 1) == 1, "allow_member_details");
            addGroupSwitch(options, t("allow_private_message"), groupInfo.optInt("allow_private_message", 1) == 1, "allow_private_message");
        }
        GroupUi.section(this, options, "群操作");
        if (isOwner) {
            addGroupMenu(options, "people", t("transfer_owner"), "", this::showTransferGroupOwnerDialog);
            View dissolve = GroupUi.menu(this, options, "exit", t("dissolve_group"), "", () -> new AlertDialog.Builder(this).setMessage(t("dissolve_group_confirm")).setNegativeButton(t("cancel"), null).setPositiveButton(t("confirm_dissolve"), (d, w) -> dissolveActiveGroup()).show());
            dissolve.setContentDescription(t("dissolve_group"));
        }
        // Owners must transfer ownership before leaving; expose the transfer action above.
        if (!isOwner) GroupUi.menu(this, options, "exit", t("leave_group"), "", () -> new AlertDialog.Builder(this).setMessage(t("leave_group_confirm")).setNegativeButton(t("cancel"), null).setPositiveButton(t("leave"), (d, w) -> {
            JSONObject request = new JSONObject();
            try { request.put("group_id", activeDetailGroupId); } catch (Exception ignored) { return; }
            ApiClient.post(this, "groups.leave", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    chatCache.clear("group", activeDetailGroupId);
                    JSONArray snapshot = readChatSnapshot();
                    for (int i = snapshot.length() - 1; i >= 0; i--) {
                        JSONObject item = snapshot.optJSONObject(i);
                        if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == activeDetailGroupId) snapshot.remove(i);
                    }
                    storeChatSnapshot(snapshot);
                    invalidateChatSnapshot();
                    exitUnavailableGroup();
                    toast(t("left_group"));
                }
                @Override public void onError(String message) { toast(message); }
            });
        }).show());
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView returnToChat = centeredText("返回群聊", 15, Color.WHITE);
        returnToChat.setTypeface(null, 1);
        returnToChat.setBackground(MallUi.primaryButton(this));
        MallUi.touch(returnToChat, 14);
        returnToChat.setOnClickListener(v -> closeSubPage());
        LinearLayout.LayoutParams returnParams = new LinearLayout.LayoutParams(-1, dp(48));
        returnParams.setMargins(dp(16), dp(6), dp(16), dp(14));
        root.addView(returnToChat, returnParams);
        openSubPage(root, activeTab);
        if (refresh) refreshGroupDetail(name, avatarText, c1, c2, groupInfo);
    }

    private void refreshGroupDetail(String name, String avatarText, String c1, String c2, JSONObject displayed) {
        Map<String, String> params = new HashMap<>();
        int groupId = activeDetailGroupId;
        params.put("group_id", String.valueOf(groupId));
        ApiClient.get(this, "groups.info", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject) || groupId != activeDetailGroupId || !subPageOpen
                        || content.getChildCount() == 0 || !("group:" + groupId).equals(content.getChildAt(0).getTag())) return;
                JSONObject latest = (JSONObject) data;
                String previous = displayed.toString();
                cacheGroupDetail(latest);
                if (!previous.equals(latest.toString())) openGroupDetailPage(latest.optString("name", name), avatarText, c1, c2, false);
            }
            @Override public void onError(String message) { }
        });
    }

    private void addGroupMember(ViewGroup parent, int userId, String name, String avatarUrl, String role, boolean canViewDetails) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(3), dp(2), dp(3), dp(8));
        String initial = name.isEmpty() ? t("group_avatar") : name.substring(0, 1);
        View memberAvatar = avatarWithRemoteImage(initial, avatarUrl, "#A8C0FF", "#2869EA", 36);
        String roleLabel = groupRoleLabel(role);
        memberAvatar.setContentDescription(name + " · " + roleLabel);
        if (canViewDetails && userId > 0) memberAvatar.setOnClickListener(v -> showGroupMemberInfo(activeDetailGroupId, userId));
        if (activeGroupId > 0 && userId > 0) memberAvatar.setOnLongClickListener(v -> {
            if (activeMessageInput != null) {
                insertMention(activeMessageInput,userId,name,-1);
                toast(t("mentioned_member", name));
            }
            return true;
        });
        cell.addView(memberAvatar);
        TextView label = centeredText(name, 11, "owner".equals(role) ? BLUE : MUTED);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setLineSpacing(0, 1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(0, dp(5), 0, 0);
        label.setContentDescription(name + " · " + roleLabel);
        cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
        TextView badge = GroupUi.roleBadge(this, role, roleLabel);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.topMargin = dp(5);
        cell.addView(badge, badgeParams);
        parent.addView(cell, new ViewGroup.LayoutParams(-1, -2));
    }

    private void addGroupSwitch(LinearLayout parent, String label, boolean checked, String key) {
        LinearLayout row = GroupUi.switchRow(this, "shield", label, checked);
        Switch toggle = (Switch) row.getChildAt(1);
        toggle.setOnCheckedChangeListener((button, value) -> {
            JSONObject request = new JSONObject();
            try { request.put("group_id", activeDetailGroupId); request.put(key, value ? 1 : 0); } catch (Exception ignored) { return; }
            ApiClient.post(this, "groups.settings", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    try {
                        JSONObject cached = new JSONObject(preferences.getString(groupDetailCacheKey(activeDetailGroupId), "{}"));
                        cached.put(key, value ? 1 : 0);
                        cacheGroupDetail(cached);
                    } catch (Exception ignored) { }
                    toast(t("group_privacy_saved"));
                }
                @Override public void onError(String message) {
                    toggle.setOnCheckedChangeListener(null);
                    toggle.setChecked(!value);
                    toggle.setOnCheckedChangeListener((buttonView, checkedNow) -> saveGroupPrivacy(key, checkedNow));
                    toast(message);
                }
            });
        });
        parent.addView(row);
    }

    private void saveGroupPrivacy(String key, boolean value) {
        JSONObject request = new JSONObject();
        try { request.put("group_id", activeDetailGroupId); request.put(key, value ? 1 : 0); } catch (Exception ignored) { return; }
        ApiClient.post(this, "groups.settings", request, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) { toast(t("group_privacy_saved")); }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void showGroupQrDialog(String groupCode, String groupName, String avatarUrl, int memberCount) {
        if (!normalizeOptionalText(groupCode).matches("[A-Z0-9]{6}")) { toast(t("group_code_retry")); return; }
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout groupHeader = row();
        groupHeader.setGravity(Gravity.CENTER_VERTICAL);
        groupHeader.addView(avatarWithRemoteImage(t("group_avatar"), avatarUrl, "#5B7CFA", "#9B5DE5", 54));
        LinearLayout groupTitle = column();
        groupTitle.setPadding(dp(12), 0, 0, 0);
        TextView title = bodyText(groupName, 17, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        groupTitle.addView(title);
        groupTitle.addView(bodyText(t("group_code_member_count", memberCount, groupCode), 12, MUTED));
        groupHeader.addView(groupTitle, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(groupHeader, new LinearLayout.LayoutParams(-1, dp(62)));
        ImageView qr = new ImageView(this);
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        qr.setBackgroundColor(Color.WHITE);
        try {
            BitMatrix matrix = new MultiFormatWriter().encode("SPXGROUP:" + groupCode, BarcodeFormat.QR_CODE, dp(230), dp(230));
            Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
            for (int x = 0; x < matrix.getWidth(); x++) for (int y = 0; y < matrix.getHeight(); y++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            qr.setImageBitmap(bitmap);
        } catch (Exception exception) { toast(t("group_qr_failed")); return; }
        box.addView(qr, new LinearLayout.LayoutParams(dp(250), dp(250)));
        box.addView(centeredText(t("group_code_people", groupCode, memberCount), 14, MUTED));
        box.addView(centeredText(t("scan_view_join_group"), 13, MUTED));
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(round(Color.WHITE, 20));
            dialog.getWindow().setLayout(dp(310), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showFindGroupDialog() {
        EditText input = formInput(t("group_code_input"), "");
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        new AlertDialog.Builder(this).setTitle(t("join_group")).setMessage(t("group_code_search_hint")).setView(input).setNegativeButton(t("cancel"), null).setPositiveButton(t("search"), (dialog, which) -> {
            String value = input.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
            if (!value.matches("[A-Z0-9]{6}")) { toast(t("group_code_format_invalid")); return; }
            joinGroupByCode(value);
        }).show();
    }

    private void joinGroupByCode(String value) {
        String groupCode = normalizeOptionalText(value).toUpperCase(java.util.Locale.ROOT);
        if (!groupCode.matches("[A-Z0-9]{6}")) { toast(t("group_code_invalid")); return; }
        Map<String, String> params = new HashMap<>();
        params.put("group_code", groupCode);
        ApiClient.get(this, "groups.search", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject)) { toast(t("group_not_found")); return; }
                JSONObject group = (JSONObject) data;
                int groupId = group.optInt("id");
                String name = group.optString("name", t("group_chat"));
                String avatarUrl = group.optString("avatar_url", "");
                int memberCount = group.optInt("member_count");
                LinearLayout preview = row();
                preview.setPadding(dp(16), dp(12), dp(16), dp(12));
                preview.addView(avatarWithRemoteImage(t("group_avatar"), avatarUrl, "#5B7CFA", "#9B5DE5", 56));
                LinearLayout previewText = column();
                previewText.setPadding(dp(12), 0, 0, 0);
                TextView previewName = bodyText(name, 17, TEXT);
                previewName.setTypeface(null, android.graphics.Typeface.BOLD);
                previewText.addView(previewName);
                previewText.addView(bodyText(t("group_code_member_count", memberCount, groupCode), 13, MUTED));
                preview.addView(previewText, new LinearLayout.LayoutParams(0, -2, 1));
                new AlertDialog.Builder(MainActivity.this).setTitle(t("group_preview")).setView(preview)
                        .setNegativeButton(t("cancel"), null).setPositiveButton(t("join_group_chat"), (d, w) -> {
                            JSONObject body = new JSONObject();
                            try { body.put("group_code", groupCode); } catch (Exception ignored) { return; }
                            ApiClient.post(MainActivity.this, "groups.join", body, new ApiClient.Callback() {
                                @Override public void onSuccess(Object result) {
                                    int joinedGroupId = result instanceof JSONObject ? ((JSONObject) result).optInt("group_id", groupId) : groupId;
                                    toast(t("joined_group"));
                                    Map<String, String> infoParams = new HashMap<>();
                                    infoParams.put("group_id", String.valueOf(joinedGroupId));
                                    ApiClient.get(MainActivity.this, "groups.info", infoParams, new ApiClient.Callback() {
                                        @Override public void onSuccess(Object detail) {
                                            if (detail instanceof JSONObject) cacheGroupDetail((JSONObject) detail);
                                            invalidateChatSnapshot();
                                            openChatWindow(0, joinedGroupId, name, t("group_avatar"), "group", "#5B7CFA", "#9B5DE5");
                                        }
                                        @Override public void onError(String error) { openChatWindow(0, joinedGroupId, name, t("group_avatar"), "group", "#5B7CFA", "#9B5DE5"); }
                                    });
                                }
                                @Override public void onError(String error) { toast(error); }
                            });
                        }).show();
            }
            @Override public void onError(String message) { toast(t("group_search_failed", message)); }
        });
    }

    private interface FriendPickerCallback { void onSelected(JSONArray contacts); }

    private void showFriendPicker(String title, String actionLabel, JSONArray contacts, FriendPickerCallback callback) {
        if (contacts == null || contacts.length() == 0) { toast(t("no_friends_add_first")); return; }
        LinearLayout panel = column();
        panel.setPadding(dp(4), dp(4), dp(4), dp(2));
        EditText filter = search(t("search_friend_hint"));
        panel.addView(filter);
        LinearLayout rows = column();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(360)));
        Set<Integer> selected = new HashSet<>();
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            String keyword = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            int shown = 0;
            for (int i = 0; i < contacts.length(); i++) {
                JSONObject contact = contacts.optJSONObject(i);
                if (contact == null) continue;
                int id = contact.optInt("id");
                String nickname = normalizeOptionalText(contact.optString("nickname", ""));
                String remark = normalizeOptionalText(contact.optString("remark", ""));
                String displayName = remark.isEmpty() ? nickname : remark;
                if (displayName.isEmpty()) displayName = t("friend");
                String spxId = contact.optString("spx_id", "");
                String searchable = (displayName + " " + nickname + " " + spxId + " " + id).toLowerCase(java.util.Locale.ROOT);
                if (!keyword.isEmpty() && !searchable.contains(keyword)) continue;
                boolean checked = selected.contains(id);
                LinearLayout item = row();
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(10), dp(9), dp(10), dp(9));
                item.setBackground(round(checked ? Color.rgb(238, 243, 255) : Color.WHITE, 9));
                item.addView(avatarWithRemoteImage(displayName.substring(0, 1), contact.optString("avatar_url", ""), "#A8C0FF", "#3F5EFB", 44));
                LinearLayout text = column();
                text.setPadding(dp(11), 0, dp(8), 0);
                TextView name = bodyText(displayName, 15, TEXT);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                text.addView(name);
                text.addView(bodyText("SPX ID " + spxId, 12, MUTED));
                item.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
                TextView mark = centeredText(checked ? t("checked") : t("unchecked"), 19, checked ? BLUE : MUTED);
                item.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(44)));
                item.setOnClickListener(v -> {
                    if (selected.contains(id)) selected.remove(id); else selected.add(id);
                    render[0].run();
                });
                rows.addView(item);
                rows.addView(separator());
                shown++;
            }
            if (shown == 0) rows.addView(note(keyword.isEmpty() ? t("no_friends") : t("no_matching_friends")));
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render[0].run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(panel).setNegativeButton(t("cancel"), null)
                .setPositiveButton(actionLabel, (d, w) -> {
                    JSONArray chosen = new JSONArray();
                    for (int i = 0; i < contacts.length(); i++) {
                        JSONObject contact = contacts.optJSONObject(i);
                        if (contact != null && selected.contains(contact.optInt("id"))) chosen.put(contact);
                    }
                    if (chosen.length() == 0) { toast(t("select_friend_first")); return; }
                    callback.onSelected(chosen);
                }).create();
        render[0].run();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(BLUE);
    }

    private void showGroupShareDialog() {
        try {
            JSONObject group = new JSONObject(preferences.getString(groupDetailCacheKey(activeDetailGroupId), "{}"));
            if (group.optInt("id") <= 0 || group.optString("group_code").isEmpty()) { toast(t("group_info_loading_retry")); return; }
            chatTools().shareGroup(group);
        } catch (Exception e) { toast(t("group_info_loading_retry")); }
    }

    void sharedGroupCardSent(int chatId, int groupId, JSONObject target, JSONObject result) {
        JSONObject message = new JSONObject();
        JSONObject event = new JSONObject();
        try {
            message.put("id", result.optInt("id"));
            message.put("sender_id", currentUserId);
            message.put("nickname", userName);
            message.put("avatar_url", remoteAvatarUrl);
            message.put("content", result.optString("content"));
            message.put("created_at", result.optString("created_at"));
            message.put("message_type", "text");
            if (groupId > 0) message.put("group_name", target.optString("name"));
            event.put("chat_id", chatId); event.put("group_id", groupId);
            if (chatId > 0) cacheSharedGroupConversation(chatId, target, message);
            updateCachedConversation(event, message, false);
            final JSONObject saved = new JSONObject(message.toString());
            chatCache.execute(() -> chatCache.put(groupId > 0 ? "group" : "chat", groupId > 0 ? groupId : chatId, saved));
            boolean viewing = chatWindowOpen && (groupId > 0 ? activeGroupId == groupId : activeChatId == chatId);
            if (viewing && activeMessageList != null && activeMessageScroll != null) {
                boolean bottom = isMessageScrollAtBottom();
                renderChatMessage(message, activeMessageList, activeMessageScroll, activeChatName, activeChatAvatar, activeChatC1, activeChatC2, false);
                if (bottom) scrollMessagesToBottom(); else showNewMessagesButton();
            }
            refreshVisibleChatList();
        } catch (Exception ignored) { }
    }

    private void cacheSharedGroupConversation(int chatId, JSONObject contact, JSONObject message) {
        JSONArray snapshot = readChatSnapshot();
        JSONObject chat = null;
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && !"group".equals(item.optString("kind")) && item.optInt("id") == chatId) { chat = item; break; }
        }
        if (chat == null) {
            chat = new JSONObject();
            snapshot.put(chat);
        }
        String nickname = normalizeOptionalText(contact.optString("nickname", t("friend")));
        String remark = normalizeOptionalText(contact.optString("remark", ""));
        try {
            chat.put("kind", "chat");
            chat.put("id", chatId);
            chat.put("peer_user_id", contact.optInt("id"));
            chat.put("nickname", nickname);
            chat.put("display_name", remark.isEmpty() ? nickname : remark);
            chat.put("avatar_url", contact.optString("avatar_url", ""));
            chat.put("last_message", t("group_invitation_message"));
            String createdAt = normalizeOptionalText(message.optString("created_at", ""));
            if (createdAt.isEmpty()) createdAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            chat.put("last_message_at", createdAt);
            chat.put("updated_at", createdAt);
            chat.put("unread_count", chat.optInt("unread_count", 0));
        } catch (Exception ignored) { }
        storeChatSnapshot(snapshot);
        invalidateChatSnapshot();
    }

    private void showTransferGroupOwnerDialog() {
        Map<String, String> params = new HashMap<>();
        params.put("group_id", String.valueOf(activeDetailGroupId));
        ApiClient.get(this, "groups.members", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONArray)) { toast(t("load_members_failed")); return; }
                JSONArray members = (JSONArray) data;
                ArrayList<JSONObject> candidates = new ArrayList<>();
                ArrayList<String> labels = new ArrayList<>();
                for (int i = 0; i < members.length(); i++) {
                    JSONObject member = members.optJSONObject(i);
                    if (member == null || member.optInt("id") == currentUserId) continue;
                    candidates.add(member);
                    labels.add(member.optString("nickname", t("group_member")));
                }
                if (candidates.isEmpty()) { toast(t("no_transfer_candidates")); return; }
                new AlertDialog.Builder(MainActivity.this).setTitle(t("transfer_owner")).setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    JSONObject target = candidates.get(which);
                    new AlertDialog.Builder(MainActivity.this).setMessage(t("transfer_owner_confirm", target.optString("nickname")))
                            .setNegativeButton(t("cancel"), null).setPositiveButton(t("confirm_transfer"), (d, w) -> {
                                JSONObject request = new JSONObject();
                                try { request.put("group_id", activeDetailGroupId); request.put("owner_user_id", target.optInt("id")); } catch (Exception ignored) { return; }
                                ApiClient.post(MainActivity.this, "groups.transfer", request, new ApiClient.Callback() {
                                    @Override public void onSuccess(Object result) { clearGroupDetailCache(activeDetailGroupId); toast(t("owner_transferred")); openGroupDetailPage(activeGroupName, t("group_avatar"), "#5B7CFA", "#9B5DE5", true); }
                                    @Override public void onError(String error) { toast(error); }
                                });
                            }).show();
                }).show();
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void dissolveActiveGroup() {
        int groupId = activeDetailGroupId;
        JSONObject request = new JSONObject();
        try { request.put("group_id", groupId); } catch (Exception ignored) { return; }
        ApiClient.post(this, "groups.dissolve", request, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                chatCache.clear("group", groupId);
                clearGroupDetailCache(groupId);
                JSONArray snapshot = readChatSnapshot();
                for (int i = snapshot.length() - 1; i >= 0; i--) {
                    JSONObject item = snapshot.optJSONObject(i);
                    if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == groupId) snapshot.remove(i);
                }
                storeChatSnapshot(snapshot);
                invalidateChatSnapshot();
                toast(t("group_dissolved"));
                exitUnavailableGroup();
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void clearGroupDetailCache(int groupId) {
        preferences.edit().remove(groupDetailCacheKey(groupId)).apply();
    }

    private void updateActiveGroupTitle() {
        if (activeChatTitle == null) return;
        String name = activeChatName;
        int count = -1;
        if (activeGroupId > 0) {
            JSONArray snapshot = readChatSnapshot();
            for (int i = 0; i < snapshot.length(); i++) {
                JSONObject item = snapshot.optJSONObject(i);
                if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == activeGroupId) {
                    name = item.optString("name", name); count = item.optInt("member_count", -1); break;
                }
            }
            if (count < 0) try {
                JSONObject detail = new JSONObject(preferences.getString(groupDetailCacheKey(activeGroupId), "{}"));
                count = detail.optInt("member_count", -1); name = detail.optString("name", name);
            } catch (Exception ignored) { }
        }
        activeChatName = name;
        activeChatTitle.setText(name + (count >= 0 ? "（" + count + "人）" : ""));
    }

    private void updateGroupMemberCount(int groupId, int count) {
        if (groupId <= 0 || count < 0) return;
        JSONArray snapshot = readChatSnapshot();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && "group".equals(item.optString("kind")) && item.optInt("id") == groupId)
                try { item.put("member_count", count); } catch (Exception ignored) { }
        }
        storeChatSnapshot(snapshot);
        try {
            String cached = preferences.getString(groupDetailCacheKey(groupId), "");
            if (!cached.isEmpty()) { JSONObject detail = new JSONObject(cached); detail.put("member_count", count); preferences.edit().putString(groupDetailCacheKey(groupId), detail.toString()).apply(); }
        } catch (Exception ignored) { }
        if (activeGroupId == groupId) updateActiveGroupTitle();
        refreshVisibleChatList();
    }

    private void refreshGroupInfo(int groupId) {
        if (groupId <= 0) return;
        final int userId = currentUserId;
        Map<String, String> params = new HashMap<>(); params.put("group_id", String.valueOf(groupId));
        ApiClient.get(this, "groups.info", params, new ApiClient.Callback() {
            public void onSuccess(Object data) { if (userId == currentUserId && data instanceof JSONObject) { cacheGroupDetail((JSONObject)data); refreshVisibleChatList(); } }
            public void onError(String error) { }
        });
    }

    private void showGroupAnnouncementOnEntry(int groupId) {
        Map<String, String> params = new HashMap<>();
        params.put("group_id", String.valueOf(groupId));
        ApiClient.get(this, "groups.info", params, new ApiClient.Callback() {
            @Override public void onSuccess(Object data) {
                if (!(data instanceof JSONObject) || !chatWindowOpen || activeGroupId != groupId) return;
                JSONObject group = (JSONObject) data;
                cacheGroupDetail(group);
                String announcement = normalizeOptionalText(group.optString("announcement", ""));
                int version = group.optInt("announcement_version");
                if (announcement.isEmpty() || version <= 0) return;
                String key = "group_announcement_seen_" + currentUserId + "_" + groupId;
                if (preferences.getInt(key, 0) >= version) return;
                new AlertDialog.Builder(MainActivity.this).setTitle(group.optString("name", t("group_announcement")))
                        .setMessage(announcement).setCancelable(false).setPositiveButton(t("know"), (dialog, which) -> preferences.edit().putInt(key, version).apply()).show();
            }
            @Override public void onError(String message) { }
        });
    }

    private void showGroupMemberInfo(int groupId, int userId) {
        if (groupId <= 0 || userId <= 0) return;
        Map<String, String> groupParams = new HashMap<>();
        groupParams.put("group_id", String.valueOf(groupId));
        ApiClient.get(this, "groups.info", groupParams, new ApiClient.Callback() {
            @Override public void onSuccess(Object groupData) {
                if (!(groupData instanceof JSONObject)) { toast(t("member_profile_unavailable")); return; }
                JSONObject group = (JSONObject) groupData;
                cacheGroupDetail(group);
                JSONObject permissions = group.optJSONObject("permissions");
                if (permissions == null || !permissions.optBoolean("view_members", false)) { toast(t("member_details_disabled")); return; }
                Map<String, String> memberParams = new HashMap<>();
                memberParams.put("group_id", String.valueOf(groupId));
                memberParams.put("q", String.valueOf(userId));
                ApiClient.get(MainActivity.this, "groups.members", memberParams, new ApiClient.Callback() {
                    @Override public void onSuccess(Object data) {
                        if (!(data instanceof JSONArray)) { toast(t("member_profile_unavailable")); return; }
                        JSONArray members = (JSONArray) data;
                        for (int i = 0; i < members.length(); i++) {
                            JSONObject member = members.optJSONObject(i);
                            if (member == null || member.optInt("id") != userId) continue;
                            try {
                                member.put("allow_private_message", group.optInt("allow_private_message", 1));
                                renderContactDetail(member);
                            } catch (Exception ignored) { toast(t("member_profile_unavailable")); }
                            return;
                        }
                        toast(t("member_left_group"));
                    }
                    @Override public void onError(String message) { toast(message); }
                });
            }
            @Override public void onError(String message) { toast(t("group_permissions_failed", message)); }
        });
    }


    private void addGroupMenu(LinearLayout parent, String icon, String title, String value, Runnable action) {
        GroupUi.menu(this, parent, icon, title, value, action);
    }

    private String groupRoleLabel(String role) {
        return "owner".equals(role) ? t("owner") : "admin".equals(role) ? t("administrator") : t("member");
    }

    private void editGroupField(String title, String value) {
        EditText input = new EditText(this);
        input.setText(value);
        new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton(t("cancel"), null).setPositiveButton(t("save"), (d, w) -> {
            String result = input.getText().toString().trim();
            if (result.isEmpty() && title.equals(t("group_name"))) { toast(t("group_name_required")); return; }
            JSONObject request = new JSONObject();
            try {
                request.put("group_id", activeDetailGroupId);
                request.put(title.equals(t("group_name")) ? "name" : "announcement", result);
            } catch (Exception ignored) { return; }
            ApiClient.post(this, "groups.update", request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    String oldName = activeGroupName;
                    if (title.equals(t("group_name"))) {
                        activeGroupName = result;
                        preferences.edit().putString("group_name_" + oldName, result).apply();
                    } else preferences.edit().putString("group_notice_" + activeGroupName, result).apply();
                    JSONArray snapshot = readChatSnapshot();
                    for (int i = 0; i < snapshot.length(); i++) {
                        JSONObject item = snapshot.optJSONObject(i);
                        if (item == null || !"group".equals(item.optString("kind")) || item.optInt("id") != activeDetailGroupId) continue;
                        try { item.put(title.equals(t("group_name")) ? "name" : "announcement", result); } catch (Exception ignored) { }
                    }
                    storeChatSnapshot(snapshot);
                    toast(t("field_saved", title));
                    invalidateChatSnapshot();
                    openGroupDetailPage(activeGroupName, t("group_avatar"), "#5B7CFA", "#9B5DE5");
                }
                @Override public void onError(String message) { toast(message); }
            });
        }).show();
    }

    private void showGroupMembersDialog() {
        if (activeDetailGroupId <= 0) return;
        if (!activeGroupCanViewMemberDetails) { toast(t("member_details_disabled")); return; }
        final int groupId = activeDetailGroupId;
        LinearLayout box = column();
        box.setPadding(dp(16), dp(12), dp(16), dp(10));
        EditText query = search(t("search_member_hint"));
        box.addView(query);
        LinearLayout results = column();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        int listHeight = Math.min(dp(390), getResources().getDisplayMetrics().heightPixels / 2);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, listHeight));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t("group_members")).setView(box).setNegativeButton(t("close"), null).create();
        int[] generation = {0};
        Runnable[] pending = {null};
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] reloadMembers = {null};
        Runnable load = () -> {
            int requestId = ++generation[0];
            Map<String, String> params = new HashMap<>();
            params.put("group_id", String.valueOf(groupId));
            String keyword = query.getText().toString().trim();
            if (!keyword.isEmpty()) params.put("q", keyword);
            ApiClient.get(MainActivity.this, "groups.members", params, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    if (requestId != generation[0] || !dialog.isShowing() || groupId != activeDetailGroupId || !(data instanceof JSONArray)) return;
                    JSONArray members = (JSONArray) data;
                    results.removeAllViews();
                    if (members.length() == 0) { results.addView(note(t("no_matching_members"))); return; }
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject member = members.optJSONObject(i);
                        if (member == null) continue;
                        LinearLayout row = row();
                        row.setPadding(dp(10), dp(10), dp(10), dp(10));
                        row.addView(avatarWithRemoteImage(chatDisplayName(member, t("group_avatar")).substring(0, 1), member.optString("avatar_url", ""), "#A8C0FF", "#3F5EFB", 42));
                        LinearLayout text = column();
                        text.setPadding(dp(12), 0, 0, 0);
                        LinearLayout nameLine = row();
                        TextView memberName = bodyText(chatDisplayName(member, t("group_member")), 15, TEXT);
                        memberName.setSingleLine(true);
                        memberName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        nameLine.addView(memberName, new LinearLayout.LayoutParams(0, -2, 1));
                        String role = member.optString("role", "member");
                        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
                        badgeParams.setMargins(dp(6), 0, dp(10), 0);
                        nameLine.addView(GroupUi.roleBadge(MainActivity.this, role, groupRoleLabel(role)), badgeParams);
                        text.addView(nameLine, new LinearLayout.LayoutParams(-1, -2));
                        String spxId = normalizeOptionalText(member.optString("spx_id", ""));
                        TextView identity = bodyText(spxId.isEmpty() ? groupRoleLabel(role) : "SPX ID: " + spxId, 12, MUTED);
                        identity.setSingleLine(true);
                        identity.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        identity.setPadding(0, dp(5), 0, 0);
                        text.addView(identity);
                        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
                        boolean canManageMember = activeGroupCanManage && !"owner".equals(role)
                                && member.optInt("id") != currentUserId
                                && (isCurrentGroupOwner() || !"admin".equals(role));
                        row.addView(bodyText(canManageMember ? t("manage") : t("details"), 13, BLUE));
                        row.setOnClickListener(v -> {
                            if (canManageMember) showGroupMemberActions(groupId, member, reloadMembers[0]);
                            else { dialog.dismiss(); showGroupMemberInfo(groupId, member.optInt("id")); }
                        });
                        results.addView(row);
                        results.addView(separator());
                    }
                }
                @Override public void onError(String message) { if (requestId == generation[0]) { results.removeAllViews(); results.addView(note(message)); } }
            });
        };
        reloadMembers[0] = load;
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending[0] != null) handler.removeCallbacks(pending[0]);
                pending[0] = load;
                handler.postDelayed(pending[0], 250);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        dialog.setOnDismissListener(d -> {
            generation[0]++;
            if (pending[0] != null) handler.removeCallbacks(pending[0]);
        });
        dialog.show();
        load.run();
    }

    private void showGroupMemberActions(int groupId, JSONObject member) {
        showGroupMemberActions(groupId, member, null);
    }

    private void showGroupMemberActions(int groupId, JSONObject member, Runnable refreshMembers) {
        String role = member.optString("role", "member");
        int userId = member.optInt("id");
        String name = chatDisplayName(member, t("group_member"));
        boolean muted = member.optInt("mute") == 1;
        if ("owner".equals(role)) { toast(t("owner_permission_locked")); return; }
        if (!activeGroupCanManage) { toast(t("no_member_manage_permission")); return; }
        if (!isCurrentGroupOwner() && "admin".equals(role)) { toast(t("admin_cannot_manage_admin")); return; }
        ArrayList<String> actionLabels = new ArrayList<>();
        if (isCurrentGroupOwner() && !"owner".equals(role)) actionLabels.add("admin".equals(role) ? t("remove_admin") : t("make_admin"));
        actionLabels.add(muted ? t("unmute") : t("mute"));
        actionLabels.add(t("remove_from_group"));
        actionLabels.add("拉黑并移出群聊");
        String[] actions = actionLabels.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(name).setItems(actions, (dialog, which) -> {
            String selected = actions[which];
            String action = selected.equals(t("remove_admin")) || selected.equals(t("make_admin")) ? "groups.members.role" : (selected.equals(t("unmute")) || selected.equals(t("mute")) ? "groups.members.mute" : "groups.members.remove");
            JSONObject request = new JSONObject();
            try {
                request.put("group_id", groupId);
                request.put("user_id", userId);
                if (action.equals("groups.members.role")) request.put("role", "admin".equals(role) ? "member" : "admin");
                if (action.equals("groups.members.mute")) request.put("mute", muted ? 0 : 1);
                if (action.equals("groups.members.remove")) request.put("ban", selected.equals("拉黑并移出群聊"));
            } catch (Exception ignored) { return; }
            Runnable send = () -> ApiClient.post(this, action, request, new ApiClient.Callback() {
                @Override public void onSuccess(Object data) {
                    toast(t("member_settings_updated"));
                    if (groupId != activeDetailGroupId) return;
                    if (refreshMembers != null) refreshMembers.run(); else showGroupMembersDialog();
                }
                @Override public void onError(String message) { toast(message); }
            });
            if (action.equals("groups.members.remove")) new AlertDialog.Builder(this).setMessage(selected.equals("拉黑并移出群聊") ? "将「" + name + "」拉黑并移出？拉黑后无法通过群号、扫码或邀请再次加入本群。" : t("remove_member_confirm", name)).setNegativeButton(t("cancel"), null).setPositiveButton(t("remove"), (d, w) -> send.run()).show();
            else send.run();
        }).show();
    }

    private boolean isCurrentGroupOwner() {
        try { return "owner".equals(new JSONObject(preferences.getString(groupDetailCacheKey(activeDetailGroupId), "{}")).optString("role")); }
        catch (Exception ignored) { return false; }
    }

    private void showGroupAdminDialog() { showGroupMembersDialog(); }

    private void showAddGroupMembersDialog() {
        if (activeDetailGroupId <= 0) return;
        Map<String, String> memberParams = new HashMap<>();
        memberParams.put("group_id", String.valueOf(activeDetailGroupId));
        ApiClient.get(this, "groups.members", memberParams, new ApiClient.Callback() {
            @Override public void onSuccess(Object memberData) {
                if (!(memberData instanceof JSONArray)) { toast(t("load_members_failed")); return; }
                HashSet<Integer> memberIds = new HashSet<>();
                JSONArray members = (JSONArray) memberData;
                for (int i = 0; i < members.length(); i++) {
                    JSONObject member = members.optJSONObject(i);
                    if (member != null) memberIds.add(member.optInt("id"));
                }
                ApiClient.get(MainActivity.this, "contacts", null, new ApiClient.Callback() {
                    @Override public void onSuccess(Object data) {
                        if (!(data instanceof JSONArray)) { toast(t("load_friends_failed")); return; }
                        JSONArray contacts = new JSONArray();
                        JSONArray allContacts = (JSONArray) data;
                        for (int i = 0; i < allContacts.length(); i++) {
                            JSONObject contact = allContacts.optJSONObject(i);
                            if (contact != null && !memberIds.contains(contact.optInt("id"))) contacts.put(contact);
                        }
                        if (contacts.length() == 0) { toast(t("no_new_friends_to_invite")); return; }
                        showFriendPicker(t("invite_friends_to_group"), t("invite_to_group"), contacts, selected -> {
                            JSONArray userIds = new JSONArray();
                            for (int i = 0; i < selected.length(); i++) {
                                JSONObject contact = selected.optJSONObject(i);
                                if (contact != null) userIds.put(contact.optInt("id"));
                            }
                            JSONObject request = new JSONObject();
                            try { request.put("group_id", activeDetailGroupId); request.put("user_ids", userIds); } catch (Exception ignored) { return; }
                            ApiClient.post(MainActivity.this, "groups.members.add", request, new ApiClient.Callback() {
                                @Override public void onSuccess(Object result) { invalidateChatSnapshot(); toast(t("friends_invited_note")); showGroupMembersDialog(); }
                                @Override public void onError(String message) { toast(message); }
                            });
                        });
                    }
                    @Override public void onError(String message) { toast(t("load_friends_failed_detail", message)); }
                });
            }
            @Override public void onError(String message) { toast(t("load_members_failed_detail", message)); }
        });
    }

    private void chooseGroupAvatar() {
        ChatGallery.open(this, "image/*", 1003);
    }

    private LinearLayout settingRow(String icon, String label, boolean arrow) {
        LinearLayout row = row();
        row.setPadding(dp(28), dp(14), dp(22), dp(14));
        row.setBackgroundColor(Color.WHITE);
        TextView iconView = centeredText(icon, 18, TEXT);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(30), dp(42)));
        TextView labelView = bodyText(label, 17, TEXT);
        row.addView(labelView, new LinearLayout.LayoutParams(0, dp(42), 1));
        if (arrow) row.addView(bodyText("›", 26, Color.rgb(176, 176, 176)), new LinearLayout.LayoutParams(dp(28), dp(42)));
        return row;
    }

    private LinearLayout settingSwitchRow(String label, boolean checked) {
        LinearLayout row = row();
        row.setPadding(dp(28), dp(13), dp(22), dp(13));
        row.setBackgroundColor(Color.WHITE);
        row.addView(bodyText(label, 17, TEXT), new LinearLayout.LayoutParams(0, dp(52), 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52)));
        return row;
    }

    private void showChatBackgroundDialog() {
        String[] options = {t("chat_background_telegram_gray"), t("chat_background_telegram_light"), t("chat_background_telegram_warm"), t("choose_gallery"), t("restore_default")};
        new AlertDialog.Builder(this).setTitle(t("chat_background_settings")).setItems(options, (dialog, which) -> {
            if (which == 3) {
                chooseChatBackground();
            } else if (which == 4) {
                chatBackgroundUri = null;
                chatBackgroundPreset = 0;
                ownProfile().edit().remove("chat_background_uri").putInt("chat_background_preset", 0).apply();
                toast(t("restored_default_background"));
            } else {
                chatBackgroundUri = null;
                chatBackgroundPreset = which + 1;
                ownProfile().edit().remove("chat_background_uri").putInt("chat_background_preset", chatBackgroundPreset).apply();
                toast(t("background_updated"));
            }
        }).show();
    }

    private void addDialogAction(LinearLayout box, String icon, String title, String desc, Runnable action) {
        LinearLayout row = row();
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(round(BG, 14));
        TextView iconView = centeredText(icon, 20, BLUE);
        iconView.setBackground(round(Color.rgb(238, 242, 255), 12));
        row.addView(iconView, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout text = column();
        text.setPadding(dp(14), 0, 0, 0);
        TextView titleView = bodyText(title, 16, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        text.addView(titleView);
        text.addView(bodyText(desc, 13, MUTED));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { action.run(); ((Dialog) box.getTag()).dismiss(); });
        box.addView(row, new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private void showBottomDialog(LinearLayout box) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        box.setTag(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(round(Color.WHITE, 24));
            window.setLayout(-1, -2);
            window.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = -1;
            params.dimAmount = 0.4f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -2);
    }

    private void showTopActionDialog(LinearLayout box) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        box.setTag(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.TOP | Gravity.RIGHT);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = dp(310);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.x = dp(12);
            params.y = dp(56);
            params.dimAmount = 0.08f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dp(310), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private LinearLayout dialogBox() {
        LinearLayout box = column();
        box.setPadding(dp(20), dp(20), dp(20), dp(24));
        box.setBackground(round(Color.WHITE, 24));
        return box;
    }

    private final class SimpleIconView extends View {
        private final String type;
        private final boolean plain;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SimpleIconView(android.content.Context context, String type) {
            this(context,type,false);
        }

        SimpleIconView(android.content.Context context, String type, boolean plain) {
            super(context);
            this.type = type;
            this.plain = plain;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth(), getHeight()) / 40f;
            canvas.save();
            canvas.scale(scale, scale);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(239, 243, 255));
            if(!plain)canvas.drawRoundRect(0, 0, 40, 40, 12, 12, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(plain?Color.rgb(75,87,104):Color.rgb(40,105,234));

            switch (type) {
                case "redpacket":
                    paint.setColor(Color.rgb(223,81,68));
                    canvas.drawRoundRect(11,7,29,33,3,3,paint);
                    canvas.drawArc(11,7,29,23,0,180,false,paint);
                    canvas.drawCircle(20,21,3,paint);
                    break;
                case "clock":
                    canvas.drawCircle(20,20,11,paint);line(canvas,20,12,20,20);line(canvas,20,20,26,23);
                    break;
                case "message":
                    canvas.drawRoundRect(8,9,32,28,5,5,paint);line(canvas,13,28,11,32);line(canvas,11,32,20,28);line(canvas,13,16,27,16);line(canvas,13,21,23,21);
                    break;
                case "gallery":
                    canvas.drawRoundRect(8, 9, 32, 31, 3, 3, paint);
                    canvas.drawCircle(15, 15, 2, paint);
                    Path landscape = new Path(); landscape.moveTo(10, 27); landscape.lineTo(17, 20); landscape.lineTo(21, 24); landscape.lineTo(25, 18); landscape.lineTo(30, 26); canvas.drawPath(landscape, paint);
                    break;
                case "camera":
                    canvas.drawRoundRect(7, 13, 33, 30, 4, 4, paint);
                    canvas.drawRoundRect(13, 9, 20, 14, 2, 2, paint);
                    canvas.drawCircle(21, 21, 6, paint); canvas.drawCircle(21, 21, 2, paint);
                    break;
                case "phone":
                    Path handset = new Path(); handset.moveTo(13, 9); handset.cubicTo(10, 10, 10, 14, 13, 20);
                    handset.cubicTo(17, 27, 23, 31, 28, 29); handset.lineTo(31, 24); handset.lineTo(25, 20);
                    handset.lineTo(22, 23); handset.cubicTo(18, 21, 16, 18, 15, 15); handset.lineTo(18, 12); handset.close(); canvas.drawPath(handset, paint);
                    break;
                case "video":
                    canvas.drawRoundRect(6, 12, 26, 28, 3, 3, paint);
                    Path lens = new Path(); lens.moveTo(27, 17); lens.lineTo(34, 13); lens.lineTo(34, 27); lens.lineTo(27, 23); lens.close(); canvas.drawPath(lens, paint);
                    break;
                case "wallet":
                    canvas.drawRoundRect(9, 12, 31, 29, 3, 3, paint);
                    line(canvas, 12, 10, 26, 10);
                    canvas.drawRoundRect(23, 17, 32, 24, 2, 2, paint);
                    canvas.drawCircle(27, 20.5f, 1, paint);
                    break;
                case "orders":
                case "history":
                    Path receipt = new Path();
                    receipt.moveTo(12, 8); receipt.lineTo(28, 8); receipt.lineTo(28, 31);
                    receipt.lineTo(25, 29); receipt.lineTo(22, 31); receipt.lineTo(19, 29);
                    receipt.lineTo(16, 31); receipt.lineTo(12, 29); receipt.close();
                    canvas.drawPath(receipt, paint);
                    line(canvas, 16, 14, 24, 14); line(canvas, 16, 19, 24, 19); line(canvas, 16, 24, 22, 24);
                    break;
                case "store":
                    canvas.drawRoundRect(9, 17, 31, 32, 2, 2, paint);
                    Path awning = new Path(); awning.moveTo(7, 17); awning.lineTo(10, 9); awning.lineTo(30, 9); awning.lineTo(33, 17); awning.close(); canvas.drawPath(awning, paint);
                    canvas.drawRect(18, 23, 25, 32, paint);
                    break;
                case "address":
                    Path pin = new Path();
                    pin.moveTo(20, 31); pin.cubicTo(18, 27, 11, 20, 11, 16);
                    pin.cubicTo(11, 11, 15, 8, 20, 8); pin.cubicTo(25, 8, 29, 11, 29, 16);
                    pin.cubicTo(29, 20, 22, 27, 20, 31); canvas.drawPath(pin, paint);
                    canvas.drawCircle(20, 16, 3, paint);
                    break;
                case "stake":
                    canvas.drawCircle(20, 20, 10, paint);
                    line(canvas, 15, 23, 19, 19); line(canvas, 19, 19, 22, 21); line(canvas, 22, 21, 26, 15);
                    line(canvas, 23, 15, 26, 15); line(canvas, 26, 15, 26, 18);
                    break;
                case "invite":
                    canvas.drawCircle(16, 15, 4, paint);
                    Path person = new Path(); person.moveTo(8, 29); person.cubicTo(8, 22, 11, 20, 16, 20); person.cubicTo(21, 20, 24, 22, 24, 29); canvas.drawPath(person, paint);
                    line(canvas, 28, 13, 28, 23); line(canvas, 23, 18, 33, 18);
                    break;
                case "help":
                    canvas.drawCircle(20, 20, 11, paint);
                    paint.setStyle(Paint.Style.FILL); paint.setTextSize(16); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    canvas.drawText("?", 16.6f, 25.5f, paint);
                    break;
                case "benefit":
                    Path star = new Path();
                    for (int i = 0; i < 10; i++) {
                        double angle = -Math.PI / 2 + i * Math.PI / 5;
                        float radius = i % 2 == 0 ? 11 : 5;
                        float x = 20 + (float) Math.cos(angle) * radius;
                        float y = 19 + (float) Math.sin(angle) * radius;
                        if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
                    }
                    star.close(); canvas.drawPath(star, paint);
                    line(canvas, 15, 28, 14, 33); line(canvas, 25, 28, 26, 33);
                    break;
                case "support":
                    canvas.drawRoundRect(8, 9, 32, 27, 6, 6, paint);
                    Path tail = new Path(); tail.moveTo(14, 27); tail.lineTo(12, 32); tail.lineTo(20, 27); canvas.drawPath(tail, paint);
                    canvas.drawCircle(15, 18, 1, paint); canvas.drawCircle(20, 18, 1, paint); canvas.drawCircle(25, 18, 1, paint);
                    break;
                case "settings":
                    canvas.drawCircle(20, 20, 8, paint); canvas.drawCircle(20, 20, 3, paint);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        line(canvas, 20 + (float) Math.cos(a) * 10, 20 + (float) Math.sin(a) * 10,
                                20 + (float) Math.cos(a) * 13, 20 + (float) Math.sin(a) * 13);
                    }
                    break;
                case "language":
                    canvas.drawCircle(20, 20, 11, paint); canvas.drawOval(15, 9, 25, 31, paint);
                    line(canvas, 9, 20, 31, 20); line(canvas, 12, 14, 28, 14); line(canvas, 12, 26, 28, 26);
                    break;
                case "lock":
                    canvas.drawRoundRect(11, 17, 29, 30, 3, 3, paint);
                    Path shackle = new Path(); shackle.moveTo(15, 18); shackle.lineTo(15, 14); shackle.cubicTo(15, 7, 25, 7, 25, 14); shackle.lineTo(25, 18); canvas.drawPath(shackle, paint);
                    canvas.drawCircle(20, 23, 1, paint); line(canvas, 20, 24, 20, 27);
                    break;
                case "cache":
                    line(canvas, 12, 12, 28, 12); line(canvas, 15, 9, 25, 9);
                    canvas.drawRoundRect(13, 13, 27, 31, 2, 2, paint);
                    line(canvas, 17, 17, 17, 27); line(canvas, 23, 17, 23, 27);
                    break;
                case "about":
                    canvas.drawCircle(20, 20, 11, paint); canvas.drawCircle(20, 14, 1, paint);
                    line(canvas, 20, 18, 20, 26);
                    break;
                case "agreement":
                    Path sheet = new Path(); sheet.moveTo(12, 8); sheet.lineTo(24, 8); sheet.lineTo(29, 13); sheet.lineTo(29, 32); sheet.lineTo(12, 32); sheet.close(); canvas.drawPath(sheet, paint);
                    line(canvas, 24, 8, 24, 13); line(canvas, 24, 13, 29, 13);
                    line(canvas, 16, 19, 25, 19); line(canvas, 16, 23, 25, 23); line(canvas, 16, 27, 23, 27);
                    break;
                default:
                    canvas.drawCircle(20, 20, 10, paint);
                    break;
            }
            canvas.restore();
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }

    private TextView title(String text) { TextView v=bodyText(text, 27, TEXT); v.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0)); return v; }
    private LinearLayout headerTitle(String title, String sub) {
        LinearLayout box = column();
        box.setPadding(dp(18), dp(14), dp(18), dp(10));
        box.setBackgroundColor(Color.WHITE);
        box.addView(this.title(title));
        box.addView(bodyText(sub, 14, MUTED));
        return box;
    }

    private EditText search(String hint) {
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint("⌕  " + tr(hint));
        search.setHintTextColor(Color.rgb(142, 154, 175));
        search.setTextColor(TEXT);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(stroke(Color.rgb(245, 247, 251), MallUi.LINE, 15, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
        p.setMargins(dp(16), dp(4), dp(16), dp(10));
        search.setLayoutParams(p);
        return search;
    }

    private View avatarWithRemoteImage(String text, String url, String start, String end, int size) {
        String fallbackText = avatarFallbackText(text);
        if (url == null || url.trim().isEmpty()) return avatar(fallbackText, start, end, size);
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        TextView fallback = avatar(fallbackText, start, end, size);
        frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(gradient(start, end, 50));
        image.setClipToOutline(true);
        image.setVisibility(View.GONE);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        loadAvatarInto(image, url.trim());
        return frame;
    }

    private String groupAvatarInitial(String name) {
        String value = normalizeOptionalText(name);
        if (value.isEmpty()) return "G";
        int end = value.offsetByCodePoints(0, 1);
        return value.substring(0, end);
    }

    private String avatarFallbackText(String text) {
        String value = normalizeOptionalText(text);
         if (value.isEmpty() || value.equals(t("group_avatar")) || value.equals(t("ui_group_avatar")) || value.equals(t("chat_group"))) return "G";
         int end = value.offsetByCodePoints(0, 1);
         return value.substring(0, end);
    }

    private TextView avatar(String text, String start, String end, int size) {
        TextView view = centeredText(text, size > 60 ? 28 : 18, Color.WHITE);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable avatarBackground = gradient(start, end, 50);
        avatarBackground.setStroke(dp(size > 60 ? 3 : 2), Color.WHITE);
        view.setBackground(avatarBackground);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return view;
    }

    private TextView circleButton(String text) {
        TextView view = centeredText(text, 18, BLUE);
        view.setBackground(round(MallUi.TINT, 14));
        MallUi.touch(view, 14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        view.setLayoutParams(p);
        return view;
    }

    private TextView badge(String text) {
        TextView view = centeredText(text, 12, Color.WHITE);
        view.setBackground(round(BLUE, 30));
        view.setPadding(dp(7), 0, dp(7), 0);
        return view;
    }

    private TextView note(String text) {
        TextView view = centeredText(text, 12, Color.rgb(154, 166, 181));
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(88));
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setContentDescription(text);
        return view;
    }
    private TextView retryState(String message, Runnable retry) {
        TextView view = note(message + "  ·  " + t("retry"));
        view.setTextColor(BLUE);
        view.setOnClickListener(v -> retry.run());
        view.setClickable(true);
        return view;
    }
    private TextView loadingState(String message) {
        TextView view = note(message);
        view.setTextColor(MUTED);
        view.setClickable(false);
        return view;
    }
    private TextView section(String text) {
        TextView view = bodyText(text.toUpperCase(), 13, MUTED);
        view.setPadding(dp(18), dp(14), dp(18), dp(7));
        return view;
    }

    private LinearLayout roundCard() {
        LinearLayout card = column();
        card.setPadding(dp(10), dp(12), dp(10), dp(12));
        card.setBackground(round(Color.WHITE, 14));
        return card;
    }

    private LinearLayout stakingMetric(String label, TextView value) {
        LinearLayout metric = column();
        metric.setGravity(Gravity.CENTER_HORIZONTAL);
        metric.addView(centeredText(label, 11, MUTED));
        value.setGravity(Gravity.CENTER);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        metric.addView(value);
        return metric;
    }

    private String resolveAppLanguage() {
        String saved = preferences.getString("language", "");
        if (saved.equals("zh-CN") || saved.equals("zh-TW") || saved.equals("en") || saved.equals("ja") || saved.equals("ko")) return saved;
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        if ("zh".equals(language)) return ("HK".equals(country) || "MO".equals(country) || "TW".equals(country)) ? "zh-TW" : "zh-CN";
        if ("ja".equals(language)) return "ja";
        if ("ko".equals(language)) return "ko";
        return "en";
    }

    private String languageDisplayName() {
        if ("zh-TW".equals(appLanguage)) return t("lang_traditional");
        if ("ja".equals(appLanguage)) return t("lang_japanese");
        if ("ko".equals(appLanguage)) return t("lang_korean");
        if ("en".equals(appLanguage)) return t("lang_english");
        return t("lang_simplified");
    }

    private String tr(String text) {
        if (text == null || "zh-CN".equals(appLanguage)) return text;
        String resourceValue = I18n.translate(this, appLanguage, text);
        if (!text.equals(resourceValue)) return resourceValue;
        String composite = I18n.translateComposite(this, appLanguage, text);
        if (!text.equals(composite)) return composite;
        return text;
    }

    private String t(String key, Object... args) {
        return I18n.t(this, appLanguage, key, args);
    }

    private TextView bodyText(String text, int size, int color) {
        TextView view = new LocalizedTextView(this);
        view.setText(tr(text));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setFontFeatureSettings("kern");
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    /** TextViews created by the UI factory also localize later async state updates. */
    private final class LocalizedTextView extends TextView {
        LocalizedTextView(Context context) { super(context); }
        @Override public void setText(CharSequence text, BufferType type) {
            // Keep ClickableSpan and quote styling attached to message text.
            super.setText(text, type);
        }
    }

    private TextView centeredText(String text, int size, int color) {
        TextView view = bodyText(text, size, color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private View separator() { View v = new View(this); v.setBackgroundColor(Color.rgb(240, 242, 245)); return v; }
    private View space(int width) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return v; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private GradientDrawable stroke(int fill, int line, int radius, int width) { GradientDrawable d = round(fill, radius); d.setStroke(dp(width), line); return d; }
    private GradientDrawable gradient(String start, String end, int radius) { GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.parseColor(start), Color.parseColor(end)}); d.setCornerRadius(dp(radius)); return d; }
    private int statusColor(String status) { return status.equals("online") ? Color.rgb(46, 204, 113) : status.equals("busy") ? Color.rgb(243, 156, 18) : MUTED; }
    private String statusLabel(String status) { return status.equals("online") ? t("online") : status.equals("busy") ? t("busy") : t("offline"); }
    private void toast(String message) { AppNotice.show(this, tr(message)); }
}
