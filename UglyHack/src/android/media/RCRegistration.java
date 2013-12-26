package android.media;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioService.LoadSoundEffectReply;
import android.media.AudioService.SoundPoolListenerThread;
import android.media.AudioService.VolumeStreamState;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.System;
import android.util.Log;
import android.util.Slog;
import android.view.VolumePanel;


public class RCRegistration {
	public RemoteController rController;
	private String TAG = "RCRegistration";
	private Context mContext;
	private static IAudioService sService;
	private final MediaFocusControl mMediaFocusControl;
	private VolumePanel mVolumePanel;
	private AudioHandler mAudioHandler;
	private final static int RCD_REG_FAILURE = 0;
    private final static int RCD_REG_SUCCESS_PERMISSION = 1;
    private final static int RCD_REG_SUCCESS_ENABLED_NOTIF = 2;
	
	public RCRegistration(Context context){
		mContext = context;
		IAudioService iService = getService();
		AudioService service = (AudioService) iService;
		mVolumePanel = new VolumePanel(mContext, service);
		mMediaFocusControl = new MediaFocusControl(mAudioHandler.getLooper(),
                mContext, /*VolumeController*/ mVolumePanel, service);
	}
	
	
	/*
	 * Mimics the AudioManager version of this method.
	 */
	public boolean registerRemoteController(RemoteController rctlr) {
        if (rctlr == null) {
            return false;
        }
        //IAudioService service = getService();
        final RemoteController.OnClientUpdateListener l = rctlr.getUpdateListener();
        final ComponentName listenerComponent;
        
        //use actual class name if EnclosingClass is null
        if (l.getClass().getEnclosingClass() == null){
        	listenerComponent = new ComponentName(mContext, l.getClass());
        } else {
        	listenerComponent = new ComponentName(mContext, l.getClass().getEnclosingClass());
        }
        
        try {
            int[] artworkDimensions = rctlr.getArtworkSize();
            boolean reg = registerRemoteController(rctlr.getRcDisplay(),
                    artworkDimensions[0]/*w*/, artworkDimensions[1]/*h*/,
                    listenerComponent);
            rctlr.setIsRegistered(reg);
            return reg;
        } catch (Exception e) {
            Log.e(TAG, "Dead object in registerRemoteController " + e);
            return false;
        }
    }
	
	
	/*
	 * Mimics the method found in MediaFocusControl. Should be invoked from the AudioManager-like method.
	 */
	protected boolean registerRemoteController(IRemoteControlDisplay rcd, int w, int h,
            ComponentName listenerComp) {
        int reg = checkRcdRegistrationAuthorization(listenerComp);
        if (reg != RCD_REG_FAILURE) {
            registerRemoteControlDisplay(rcd, w, h, listenerComp);
            return true;
        } else {
            Slog.w(TAG, "Access denied to process: " + Binder.getCallingPid() +
                    ", must have permission " + android.Manifest.permission.MEDIA_CONTENT_CONTROL +
                    " or be an enabled NotificationListenerService for registerRemoteController");
            return false;
        }
    }
	
	/*
	 * Used to directly register the RemoteController without even internal security checks.
	 * Mostly for debugging purposes.
	 */
	public boolean forceRegisterRemoteController(RemoteController rctlr) {
		if (rctlr == null) {
			return false;
		}
		try {
			int[] artworkDimensions = rctlr.getArtworkSize();
			genericInvokMethod(mMediaFocusControl, "registerRemoteControlDisplay_int", 4, rctlr.getRcDisplay(),
                    artworkDimensions[0]/*w*/, artworkDimensions[1]/*h*/, null);
			rctlr.setIsRegistered(true);
            return true;
		} catch (Exception e) {
            Log.e(TAG, "Dead object in registerRemoteController " + e);
            return false;
        }
		
	}
	
	/*
	 * Direcly invokes the registerRemoteControlDisplay_int method from MediaFocusControl object. 
	 */
	private void registerRemoteControlDisplay(IRemoteControlDisplay rcd, int w, int h, ComponentName listenerComp) {
		genericInvokMethod(mMediaFocusControl, "registerRemoteControlDisplay_int", 4, rcd, w, h, null);
	}
	
	/*
	 * Uses reflection to invoke a private method.
	 * This is necessary to force the registration.
	 */
	public static Object genericInvokMethod(Object obj, String methodName,
            int paramCount, Object... params) {
        Method method;
        Object requiredObj = null;
        Object[] parameters = new Object[paramCount];
        Class<?>[] classArray = new Class<?>[paramCount];
        for (int i = 0; i < paramCount; i++) {
            parameters[i] = params[i];
            classArray[i] = params[i].getClass();
        }
        try {
            method = obj.getClass().getDeclaredMethod(methodName, classArray);
            method.setAccessible(true);
            requiredObj = method.invoke(obj, params);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return requiredObj;
    }
	
	private static IAudioService getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }
	
	private int checkRcdRegistrationAuthorization(ComponentName listenerComp) {
        // MEDIA_CONTENT_CONTROL permission check
        if (PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MEDIA_CONTENT_CONTROL)) {
            return RCD_REG_SUCCESS_PERMISSION;
        }

        // ENABLED_NOTIFICATION_LISTENERS settings check
        if (listenerComp != null) {
            // this call is coming from an app, can't use its identity to read secure settings
            final long ident = Binder.clearCallingIdentity();
            try {
                final int currentUser = ActivityManager.getCurrentUser();
                final String enabledNotifListeners = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                        currentUser);
                if (enabledNotifListeners != null) {
                    final String[] components = enabledNotifListeners.split(":");
                    for (int i=0; i<components.length; i++) {
                        final ComponentName component =
                                ComponentName.unflattenFromString(components[i]);
                        if (component != null) {
                            if (listenerComp.equals(component)) {
                                return RCD_REG_SUCCESS_ENABLED_NOTIF;
                            }
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return RCD_REG_FAILURE;
	}
	
	public void testMethod(){
		
		AudioService outer = new AudioService(mContext);
		// List all available constructors.
        // We must use the method getDeclaredConstructors() instead
        // of getConstructors() to get also private constructors.
        for (Constructor<?> ctor : AudioService.AudioHandler.class
                .getDeclaredConstructors()) {
            System.out.println(ctor);
        }

        try {
            // Try to get the constructor with the expected signature.
            Constructor<AudioHandler> ctor = AudioService.AudioHandler.class
                    .getDeclaredConstructor(AudioService.class);
            // This forces the security manager to allow a call
            ctor.setAccessible(true);

            // the call
            try {
                AudioService.AudioHandler inner = ctor.newInstance(outer);
                System.out.println(inner);
            } catch (InstantiationException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } catch (NoSuchMethodException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (SecurityException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
	}
	
	public void testMethodTwo(){
		//we need an outer class object to use the inner object constructor
        //(the inner class object needs to know about its parent object)
        AudioService outerObject = new AudioService(mContext);

        //let's get the inner class 
        //(we know that the outer class has only one inner class, so we can use index 0)
        Class<?> innerClass = AudioService.class.getDeclaredClasses()[0];

        //we need the constructor to pass the outer object info and change its 
        //accessibility
        Constructor<?> constructor = innerClass.getDeclaredConstructors()[0];

        //the default constructor of the private class is also private, so we need to
        //make it accessible
        constructor.setAccessible(true);

        //now we are ready to create an inner class object
        Object innerObject = constructor.newInstance(outerObject);
	}
}