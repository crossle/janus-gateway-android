package in.minewave.janusvideoroom;


import org.webrtc.PeerConnection;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.math.BigInteger;

public class JanusConnection {
    public BigInteger handleId;
    public PeerConnection peerConnection;
    public PeerConnectionClient.SDPObserver sdpObserver;
    public VideoTrack videoTrack;
    public SurfaceViewRenderer videoRender;
    public boolean type;
}
