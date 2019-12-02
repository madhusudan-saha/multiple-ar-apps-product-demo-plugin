package edu.buffalo.cse622.plugins;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.SkeletonNode;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ExternalTexture;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.ux.ArFragment;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class FrameOperations {

    private static final String TAG = "ProductDemoPlugin:" + FrameOperations.class.getSimpleName();

    private Resources dynamicResources;
    private ArFragment arFragment;
    private Context context;
    private HashSet<AnchorNode> pluginObjects;

    private AnchorNode videoAnchorNode;
    private ViewRenderable macbookTextRenderable;
    private ModelRenderable macbookVideoRenderable;
    private ExternalTexture texture;
    private MediaPlayer mediaPlayer;

    // Controls the height of the video in world space.
    private static final float VIDEO_HEIGHT_METERS = 0.85f;

    // Created enum because there is an issue with MediaPlayer's stop(). Can't start() after stop() without preparing MediaPlayer again.
    private enum MediaPlayerStates {
        PREPARED, PLAYING, STOPPED, PAUSED
    }

    private MediaPlayerStates currentMediaPlayerState;

    /**
     * Constructor does all the resources loading that the plugin requires.
     *
     * @param dynamicResources The Resources object is already initialized and passed by MetaApp which helps the plugin to be "aware" of its own resources.
     * @param arFragment       ArFragment object passed by MetaApp.
     */
    public FrameOperations(Resources dynamicResources, ArFragment arFragment, HashSet<AnchorNode> pluginObjects) {
        this.dynamicResources = dynamicResources;
        this.arFragment = arFragment;
        this.context = arFragment.getContext();
        this.pluginObjects = pluginObjects;

        // Create an ExternalTexture for displaying the contents of the video.
        texture = new ExternalTexture();
        mediaPlayer = new MediaPlayer();
        setMediaPlayer();

        int macbookVideoTextLayoutId = dynamicResources.getIdentifier("text_view", "layout", "edu.buffalo.cse622.plugins");
        XmlResourceParser macbookVideoTextViewXml = dynamicResources.getLayout(macbookVideoTextLayoutId);
        View macbookVideoTextView = LayoutInflater.from(context).inflate(macbookVideoTextViewXml, null);

        ViewRenderable.builder()
                .setView(context, macbookVideoTextView)
                .build()
                .thenAccept(
                        (renderable) -> {
                            macbookTextRenderable = renderable;
                        });

        CompletableFuture<ModelRenderable> macbookVideoStage =
                ModelRenderable.builder().setSource(context, () -> {
                    InputStream inputStream = null;
                    try {
                        AssetManager assetManager = dynamicResources.getAssets();
                        inputStream = assetManager.open("macbook_pro_replace_hard_drive.sfb");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    return inputStream;
                }).build();

        CompletableFuture.allOf(macbookVideoStage)
                .handle(
                        (notUsed, throwable) -> {
                            // When you build a Renderable, Sceneform loads its resources in the background while
                            // returning a CompletableFuture. Call handle(), thenAccept(), or check isDone()
                            // before calling get().

                            if (throwable != null) {
                                Log.d(TAG, "handle: " + "Unable to load renderable", throwable);
                                return null;
                            }

                            try {
                                macbookVideoRenderable = macbookVideoStage.get();
                                macbookVideoRenderable.getMaterial().setExternalTexture("videoTexture", texture);
                            } catch (InterruptedException | ExecutionException ex) {
                                Log.d(TAG, "handle: " + "Unable to load renderable", ex);
                            }

                            return null;
                        });

        Session session = arFragment.getArSceneView().getSession();
        Config config = session.getConfig();
        setupAugmentedImagesDb(config, session);
    }

    /**
     * This is where we do most of our operations on the frame.
     *
     * @param frame
     * @return
     */
    private void processFrame(Frame frame) {
        if (macbookVideoRenderable == null || frame == null) {
            return;
        }

        Collection<AugmentedImage> augmentedImages = frame.getUpdatedTrackables(AugmentedImage.class);
        for (AugmentedImage augmentedImage : augmentedImages) {
            if (augmentedImage.getTrackingState() == TrackingState.TRACKING &&
                    augmentedImage.getTrackingMethod() == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                if (augmentedImage.getName().equals("macbook_pro_2018_keyboard")) {
                    Log.d(TAG, "Detected Macbook Pro Keyboard!");
                    //Toast.makeText(context, "Detected Macbook Pro Keyboard!", Toast.LENGTH_LONG).show();

                    if (videoAnchorNode == null) {
                        videoAnchorNode = new AnchorNode(augmentedImage.createAnchor(augmentedImage.getCenterPose()));

                        if (currentMediaPlayerState == MediaPlayerStates.STOPPED) {
                            setMediaPlayer();
                        }
                    } else {
                        videoAnchorNode.setAnchor(augmentedImage.createAnchor(augmentedImage.getCenterPose()));
                    }

                    // Start playing the video.
                    if (currentMediaPlayerState == MediaPlayerStates.PREPARED || currentMediaPlayerState == MediaPlayerStates.STOPPED) {
                        Log.e(TAG, currentMediaPlayerState.name());
                        Toast.makeText(context, currentMediaPlayerState.name(), Toast.LENGTH_LONG).show();
                        // Create a node to render the video and add it to the anchor.
                        Node node = new Node();
                        node.setParent(videoAnchorNode);

                        // Set the scale of the node so that the aspect ratio of the video is correct.
                        float videoWidth = mediaPlayer.getVideoWidth();
                        float videoHeight = mediaPlayer.getVideoHeight();
                        node.setLocalScale(
                                new Vector3(
                                        VIDEO_HEIGHT_METERS * (videoWidth / videoHeight) * 0.25f, VIDEO_HEIGHT_METERS * 0.25f, 0.25f));
                        node.setLocalRotation(Quaternion.axisAngle(new Vector3(-1f, 0, 0), 90f));

                        mediaPlayer.start();
                        currentMediaPlayerState = MediaPlayerStates.PLAYING;

                        // Wait to set the renderable until the first frame of the  video becomes available.
                        // This prevents the renderable from briefly appearing as a black quad before the video
                        // plays.
                        texture
                                .getSurfaceTexture()
                                .setOnFrameAvailableListener(
                                        (SurfaceTexture surfaceTexture) -> {
                                            SkeletonNode videoNode = new SkeletonNode();
                                            videoNode.setParent(node);
                                            videoNode.setRenderable(macbookVideoRenderable);

                                            // Attach video title
                                            Node titleNode = new Node();
                                            titleNode.setParent(node);
                                            videoNode.setBoneAttachment("Macbook Pro replace SSD", titleNode);

                                            // Use bone node to display ViewRenderable
                                            Node videoTextNode = new Node();
                                            videoTextNode.setRenderable(macbookTextRenderable);
                                            videoTextNode.setParent(titleNode);
                                            videoTextNode.setWorldRotation(videoNode.getWorldRotation());

                                            Vector3 videoBack = videoNode.getBack();
                                            videoTextNode.setLocalPosition(videoBack);
                                            Vector3 pos = videoTextNode.getWorldPosition();
                                            pos.x -= .5f;
                                            pos.y += .5f;

                                            TextView textView = (TextView) macbookTextRenderable.getView();
                                            textView.setText("Macbook Pro replace SSD");

                                            texture.getSurfaceTexture().setOnFrameAvailableListener(null);

                                            videoNode.setOnTapListener(
                                                    (HitTestResult hitTestResult, MotionEvent event) -> {
                                                        if (currentMediaPlayerState == MediaPlayerStates.PLAYING) {
                                                            mediaPlayer.pause();
                                                            Log.d(TAG, "Video paused.");
                                                            Toast.makeText(context, "Video paused!", Toast.LENGTH_LONG).show();

                                                            currentMediaPlayerState = MediaPlayerStates.PAUSED;
                                                        } else {
                                                            mediaPlayer.start();
                                                            Log.d(TAG, "Video started.");
                                                            Toast.makeText(context, "Video resumed!", Toast.LENGTH_LONG).show();

                                                            currentMediaPlayerState = MediaPlayerStates.PLAYING;
                                                        }
                                                    });

                                            renderObject(videoAnchorNode);
                                        });
                    }
                }
            }
        }
    }

    /**
     * This is the method that is invoked when user input for this plugin is activated in MetaApp and user taps on a plane.
     *
     * @param hitResult
     */
    private void planeTap(HitResult hitResult) {
    }

    /**
     * This is invoked when the MetaApp clears or disables this plugin.
     */
    private void onDestroy() {
        mediaPlayer.stop();
        mediaPlayer.reset();
        videoAnchorNode = null;

        currentMediaPlayerState = MediaPlayerStates.STOPPED;
    }

    private void setMediaPlayer() {
        // This is how we load a layout resource.
        int macbookVideoId = dynamicResources.getIdentifier("macbook_pro_replace_hard_drive", "raw", "edu.buffalo.cse622.plugins");
        AssetFileDescriptor macbookVideoInputStreamFD = dynamicResources.openRawResourceFd(macbookVideoId);

        try {
            // Create an Android MediaPlayer to capture the video on the external texture's surface.
            mediaPlayer.setDataSource(macbookVideoInputStreamFD);
            mediaPlayer.prepare();
            mediaPlayer.setSurface(texture.getSurface());
            mediaPlayer.setLooping(false);

            currentMediaPlayerState = MediaPlayerStates.PREPARED;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean setupAugmentedImagesDb(Config config, Session session) {
        AugmentedImageDatabase augmentedImageDatabase = config.getAugmentedImageDatabase();
        Bitmap bitmap = loadAugmentedImage("macbook_pro_2018_keyboard.jpg");
        if (bitmap == null) {
            return false;
        }

        augmentedImageDatabase.addImage("macbook_pro_2018_keyboard", bitmap);
        config.setAugmentedImageDatabase(augmentedImageDatabase);

        session.configure(config);

        return true;
    }

    private Bitmap loadAugmentedImage(String fileName) {
        try {
            AssetManager assetManager = dynamicResources.getAssets();
            InputStream is = assetManager.open(fileName);
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            Log.e(TAG, "loadAugmentedImage: " + "IO Exception", e);
        }
        return null;
    }

    private void renderObject(AnchorNode anchorNode) {
        if (anchorNode != null) {
            anchorNode.setParent(arFragment.getArSceneView().getScene());
            pluginObjects.add(anchorNode);
        }
    }
}

