package in.minewave.janusvideoroom;

import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.ByteString;


public class WebSocketChannel {
    private static final String TAG = "WebSocketChannel";

    private WebSocket mWebSocket;
    private ConcurrentHashMap<String, JanusTransaction> transactions = new ConcurrentHashMap<>();
    private ConcurrentHashMap<BigInteger, JanusHandle> handles = new ConcurrentHashMap<>();
    private ConcurrentHashMap<BigInteger, JanusHandle> feeds = new ConcurrentHashMap<>();
    private Handler mHandler;
    private BigInteger mSessionId;
    private JanusRTCInterface delegate;

    public WebSocketChannel() {
        mHandler = new Handler();
    }

    public void initConnection(String url) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addNetworkInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Interceptor.Chain chain) throws IOException {
                        Request.Builder builder = chain.request().newBuilder();
                        builder.addHeader("Sec-WebSocket-Protocol", "janus-protocol");
                        return chain.proceed(builder.build());
                    }
                }).connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(url).build();
        mWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.e(TAG, "onOpen");
                createSession();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.e(TAG, "onMessage");
                WebSocketChannel.this.onMessage(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.e(TAG, "onClosing");
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "onFailure" + t.toString());
            }
        });
    }

    private void onMessage(String message) {
        Log.e(TAG, "onMessage" + message);
        try {
            JSONObject jo = new JSONObject(message);
            String janus = jo.optString("janus");
            if (janus.equals("success")) {
                String transaction = jo.optString("transaction");
                JanusTransaction jt = transactions.get(transaction);
                if (jt.success != null) {
                    jt.success.success(jo);
                }
                transactions.remove(transaction);
            } else if (janus.equals("error")) {
                String transaction = jo.optString("transaction");
                JanusTransaction jt = transactions.get(transaction);
                if (jt.error != null) {
                    jt.error.error(jo);
                }
                transactions.remove(transaction);
            } else if (janus.equals("ack")) {
                Log.e(TAG, "Just an ack");
            } else {
                JanusHandle handle = handles.get(new BigInteger(jo.optString("sender")));
                if (handle == null) {
                    Log.e(TAG, "missing handle");
                } else if (janus.equals("event")) {
                    JSONObject plugin = jo.optJSONObject("plugindata").optJSONObject("data");
                    if (plugin.optString("videoroom").equals("joined")) {
                        handle.onJoined.onJoined(handle);
                    }

                    JSONArray publishers = plugin.optJSONArray("publishers");
                    if (publishers != null && publishers.length() > 0) {
                        for (int i = 0, size = publishers.length(); i <= size - 1; i++) {
                            JSONObject publisher = publishers.optJSONObject(i);
                            BigInteger feed = new BigInteger(publisher.optString("id"));
                            String display = publisher.optString("display");
                            subscriberCreateHandle(feed, display);
                        }
                    }

                    String leaving = plugin.optString("leaving");
                    if (!TextUtils.isEmpty(leaving)) {
                        JanusHandle jhandle = feeds.get(new BigInteger(leaving));
                        jhandle.onLeaving.onJoined(jhandle);
                    }

                    JSONObject jsep = jo.optJSONObject("jsep");
                    if (jsep != null) {
                       handle.onRemoteJsep.onRemoteJsep(handle, jsep);
                    }

                } else if (janus.equals("detached")) {
                    handle.onLeaving.onJoined(handle);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void createSession() {
        String transaction = randomString(12);
        JanusTransaction jt = new JanusTransaction();
        jt.tid =  transaction;
        jt.success = new TransactionCallbackSuccess() {
            @Override
            public void success(JSONObject jo) {
                mSessionId = new BigInteger(jo.optJSONObject("data").optString("id"));
                mHandler.post(fireKeepAlive);
                publisherCreateHandle();
            }
        };
        jt.error = new TransactionCallbackError() {
            @Override
            public void error(JSONObject jo) {
            }
        };
        transactions.put(transaction, jt);
        JSONObject msg = new JSONObject();
        try {
            msg.putOpt("janus", "create");
            msg.putOpt("transaction", transaction);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(msg.toString());
    }

    private void publisherCreateHandle() {
        String transaction = randomString(12);
        JanusTransaction jt = new JanusTransaction();
        jt.tid = transaction;
        jt.success = new TransactionCallbackSuccess() {
            @Override
            public void success(JSONObject jo) {
                JanusHandle janusHandle = new JanusHandle();
                janusHandle.handleId = new BigInteger(jo.optJSONObject("data").optString("id"));
                janusHandle.onJoined = new OnJoined() {
                    @Override
                    public void onJoined(JanusHandle jh) {
                        delegate.onPublisherJoined(jh.handleId);
                    }
                };
                janusHandle.onRemoteJsep = new OnRemoteJsep() {
                    @Override
                    public void onRemoteJsep(JanusHandle jh,  JSONObject jsep) {
                        delegate.onPublisherRemoteJsep(jh.handleId, jsep);
                    }
                };
                handles.put(janusHandle.handleId, janusHandle);
                publisherJoinRoom(janusHandle);
            }
        };
        jt.error = new TransactionCallbackError() {
            @Override
            public void error(JSONObject jo) {
            }
        };
        transactions.put(transaction, jt);
        JSONObject msg = new JSONObject();
        try {
            msg.putOpt("janus", "attach");
            msg.putOpt("plugin", "janus.plugin.videoroom");
            msg.putOpt("transaction", transaction);
            msg.putOpt("session_id", mSessionId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(msg.toString());
    }

    private void publisherJoinRoom(JanusHandle handle) {
        JSONObject msg = new JSONObject();
        JSONObject body = new JSONObject();
        try {
            body.putOpt("request", "join");
            body.putOpt("room", 1234);
            body.putOpt("ptype", "publisher");
            body.putOpt("display", "Android webrtc");

            msg.putOpt("janus", "message");
            msg.putOpt("body", body);
            msg.putOpt("transaction", randomString(12));
            msg.putOpt("session_id", mSessionId);
            msg.putOpt("handle_id", handle.handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(msg.toString());
    }

    public void publisherCreateOffer(final BigInteger handleId, final SessionDescription sdp) {
        JSONObject publish = new JSONObject();
        JSONObject jsep = new JSONObject();
        JSONObject message = new JSONObject();
        try {
            publish.putOpt("request", "configure");
            publish.putOpt("audio", true);
            publish.putOpt("video", true);

            jsep.putOpt("type", sdp.type);
            jsep.putOpt("sdp", sdp.description);

            message.putOpt("janus", "message");
            message.putOpt("body", publish);
            message.putOpt("jsep", jsep);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", mSessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(message.toString());
    }

    public void subscriberCreateAnswer(final BigInteger handleId, final SessionDescription sdp) {
        JSONObject body = new JSONObject();
        JSONObject jsep = new JSONObject();
        JSONObject message = new JSONObject();

        try {
            body.putOpt("request", "start");
            body.putOpt("room", 1234);

            jsep.putOpt("type", sdp.type);
            jsep.putOpt("sdp", sdp.description);
            message.putOpt("janus", "message");
            message.putOpt("body", body);
            message.putOpt("jsep", jsep);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", mSessionId);
            message.putOpt("handle_id", handleId);
            Log.e(TAG, "-------------"  + message.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        mWebSocket.send(message.toString());
    }

    public void trickleCandidate(final BigInteger handleId, final IceCandidate iceCandidate) {
        JSONObject candidate = new JSONObject();
        JSONObject message = new JSONObject();
        try {
            candidate.putOpt("candidate", iceCandidate.sdp);
            candidate.putOpt("sdpMid", iceCandidate.sdpMid);
            candidate.putOpt("sdpMLineIndex", iceCandidate.sdpMLineIndex);

            message.putOpt("janus", "trickle");
            message.putOpt("candidate", candidate);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", mSessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(message.toString());
    }

    public void trickleCandidateComplete(final BigInteger handleId) {
        JSONObject candidate = new JSONObject();
        JSONObject message = new JSONObject();
        try {
            candidate.putOpt("completed", true);

            message.putOpt("janus", "trickle");
            message.putOpt("candidate", candidate);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", mSessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void subscriberCreateHandle(final BigInteger feed, final String display) {
        String transaction = randomString(12);
        JanusTransaction jt = new JanusTransaction();
        jt.tid = transaction;
        jt.success = new TransactionCallbackSuccess() {
            @Override
            public void success(JSONObject jo) {
                JanusHandle janusHandle = new JanusHandle();
                janusHandle.handleId = new BigInteger(jo.optJSONObject("data").optString("id"));
                janusHandle.feedId = feed;
                janusHandle.display = display;
                janusHandle.onRemoteJsep = new OnRemoteJsep() {
                    @Override
                    public void onRemoteJsep(JanusHandle jh, JSONObject jsep) {
                        delegate.subscriberHandleRemoteJsep(jh.handleId, jsep);
                    }
                };
                janusHandle.onLeaving = new OnJoined() {
                    @Override
                    public void onJoined(JanusHandle jh) {
                        subscriberOnLeaving(jh);
                    }
                };
                handles.put(janusHandle.handleId, janusHandle);
                feeds.put(janusHandle.feedId, janusHandle);
                subscriberJoinRoom(janusHandle);
            }
        };
        jt.error = new TransactionCallbackError() {
            @Override
            public void error(JSONObject jo) {
            }
        };

        transactions.put(transaction, jt);
        JSONObject msg = new JSONObject();
        try {
            msg.putOpt("janus", "attach");
            msg.putOpt("plugin", "janus.plugin.videoroom");
            msg.putOpt("transaction", transaction);
            msg.putOpt("session_id", mSessionId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        mWebSocket.send(msg.toString());
    }

    private void subscriberJoinRoom(JanusHandle handle) {

        JSONObject msg = new JSONObject();
        JSONObject body = new JSONObject();
        try {
            body.putOpt("request", "join");
            body.putOpt("room", 1234);
            body.putOpt("ptype", "listener");
            body.putOpt("feed", handle.feedId);

            msg.putOpt("janus", "message");
            msg.putOpt("body", body);
            msg.putOpt("transaction", randomString(12));
            msg.putOpt("session_id", mSessionId);
            msg.putOpt("handle_id", handle.handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(msg.toString());
    }

    private void subscriberOnLeaving(final JanusHandle handle) {
        String transaction = randomString(12);
        JanusTransaction jt = new JanusTransaction();
        jt.tid = transaction;
        jt.success = new TransactionCallbackSuccess() {
            @Override
            public void success(JSONObject jo) {
                delegate.onLeaving(handle.handleId);
                handles.remove(handle.handleId);
                feeds.remove(handle.feedId);
            }
        };
        jt.error = new TransactionCallbackError() {
            @Override
            public void error(JSONObject jo) {
            }
        };

        transactions.put(transaction, jt);

        JSONObject jo = new JSONObject();
        try {
            jo.putOpt("janus", "detach");
            jo.putOpt("transaction", transaction);
            jo.putOpt("session_id", mSessionId);
            jo.putOpt("handle_id", handle.handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(jo.toString());
    }

    private void keepAlive() {
        String transaction = randomString(12);
        JSONObject msg = new JSONObject();
        try {
            msg.putOpt("janus", "keepalive");
            msg.putOpt("session_id", mSessionId);
            msg.putOpt("transaction", transaction);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mWebSocket.send(msg.toString());
    }

    private Runnable fireKeepAlive = new Runnable() {
        @Override
        public void run() {
            keepAlive();
            mHandler.postDelayed(fireKeepAlive, 30000);
        }
    };

    public void setDelegate(JanusRTCInterface delegate) {
        this.delegate = delegate;
    }

    private String randomString(Integer length) {
        final String str = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final Random rnd = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(str.charAt(rnd.nextInt(str.length())));
        }
        return sb.toString();
    }
}
