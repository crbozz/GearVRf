/* Copyright 2015 Samsung Electronics Co., LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gearvrf.io;

import org.gearvrf.GVRContext;
import org.gearvrf.GVRCursorController;
import org.gearvrf.GVRDrawFrameListener;
import org.gearvrf.GVRScene;
import org.gearvrf.GVRSceneObject;
import org.gearvrf.utility.Log;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import android.util.SparseArray;
import android.view.InputDevice;

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Use this class to translate MotionEvents generated by a mouse to manipulate
 * {@link GVRMouseController}s.
 */
final class GVRMouseDeviceManager implements GVRDrawFrameListener {
    private static final String TAG = "GVRMouseDeviceManager";
    private static final String THREAD_NAME = "GVRMouseManagerThread";
    private EventHandlerThread thread;
    private SparseArray<GVRMouseController> controllers;
    private boolean threadStarted;
    private GVRContext gvrContext;


    GVRMouseDeviceManager(GVRContext context) {
        this.gvrContext = context;
        thread = new EventHandlerThread(THREAD_NAME);
        controllers = new SparseArray<>();
    }

    GVRBaseController getCursorController(GVRContext context, String name, int vendorId, int productId) {
        Log.d(TAG, "Creating Mouse Device");
        startThread();
        GVRMouseController controller = new GVRMouseController(context,
                GVRControllerType.MOUSE, name, vendorId, productId, this);
        int id = controller.getId();
        synchronized (controllers) {
            controllers.append(id, controller);
        }
        return controller;
    }

    void removeCursorController(GVRBaseController controller) {
        int id = controller.getId();
        synchronized (controllers) {
            controllers.remove(id);

            // stopThread the thread if no more devices are online
            if (controllers.size() == 0) {
                forceStopThread();
            }
        }
    }

    @Override
    public void onDrawFrame(float frameTime) {
        thread.updatePosition();
    }

    private static class GVRMouseController extends GVRBaseController {
        private static final KeyEvent BUTTON_1_DOWN = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_1);
        private static final KeyEvent BUTTON_1_UP = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_1);

        private GVRMouseDeviceManager deviceManager;
        private GVRContext context;
        private final Vector3f position;
        private final Quaternionf rotation;
        private final Matrix4f scratchMatrix = new Matrix4f();
        private final Vector3f scratchVector = new Vector3f();
        private GVRSceneObject internalObject;
        private boolean isEnabled;

        GVRMouseController(GVRContext context, GVRControllerType controllerType, String name, int
                vendorId, int productId, GVRMouseDeviceManager deviceManager) {
            super(controllerType, name, vendorId, productId);
            this.context = context;
            this.deviceManager = deviceManager;
            position = new Vector3f(0.0f, 0.0f, -1.0f);
            rotation = new Quaternionf();
            isEnabled = isEnabled();
        }

        @Override
        public void setEnable(boolean enable) {
            if (!isEnabled && enable) {
                isEnabled = true;
                deviceManager.startThread();
                //set the enabled flag on the handler thread
                deviceManager.thread.setEnable(getId(), true);
            } else if (isEnabled && !enable) {
                isEnabled = false;
                //set the disabled flag on the handler thread
                deviceManager.thread.setEnable(getId(), false);
                deviceManager.stopThread();
            }
        }

        @Override
        protected void setScene(GVRScene scene) {
            if (!deviceManager.threadStarted) {
                super.setScene(scene);
            } else {
                deviceManager.thread.setScene(getId(), scene);
            }
        }

        void callParentSetEnable(boolean enable){
            super.setEnable(enable);
        }

        void callParentSetScene(GVRScene scene) {
            super.setScene(scene);
        }

        void callParentInvalidate() {
            super.invalidate();
        }

        @Override
        public void invalidate() {
            if (!deviceManager.threadStarted) {
                //do nothing
                return;
            }
            deviceManager.thread.sendInvalidate(getId());
        }

        @Override
        protected void setKeyEvent(KeyEvent keyEvent) {
            super.setKeyEvent(keyEvent);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                return deviceManager.thread.submitKeyEvent(getId(), event);
            } else {
                return false;
            }
        }

        @Override
        public boolean dispatchMotionEvent(MotionEvent event) {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                return deviceManager.thread.submitMotionEvent(getId(), event);
            } else {
                return false;
            }
        }

        @Override
        public void setSceneObject(GVRSceneObject object) {
            this.internalObject = object;
            if (internalObject != null) {
                internalObject.getTransform().setPosition(position.x, position.y, position.z);
            }
        }

        @Override
        public GVRSceneObject getSceneObject() {
            return internalObject;
        }

        @Override
        public void resetSceneObject() {
            internalObject = null;
        }

        private boolean processMouseEvent(float x, float y, float z,
                                          MotionEvent event) {
            GVRScene scene = context.getMainScene();
            if (scene != null) {
                float depth = position.z;
                if (((depth + z) <= getNearDepth())
                        && ((depth + z) >= getFarDepth())) {
                    float frustumWidth, frustumHeight;
                    depth = depth + z;

                    // calculate the frustum using the aspect ratio and FOV
                    // http://docs.unity3d.com/Manual/FrustumSizeAtDistance.html
                    float aspectRatio = scene.getMainCameraRig().getCenterCamera().getAspectRatio();
                    float fovY = scene.getMainCameraRig().getCenterCamera().getFovY();
                    float frustumHeightMultiplier = (float) Math.tan(Math.toRadians(fovY / 2)) * 2.0f;
                    frustumHeight = frustumHeightMultiplier * depth;
                    frustumWidth = frustumHeight * aspectRatio;

                    position.x = (frustumWidth * -x)/2.0f;
                    position.y = (frustumHeight * -y)/2.0f;
                    position.z = depth;
                }

                /*
                 * The mouse does not report a key event against the primary
                 * button click. Instead we generate a synthetic KeyEvent
                 * against the mouse.
                 */
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    setKeyEvent(BUTTON_1_DOWN);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    setKeyEvent(BUTTON_1_UP);
                }
                setMotionEvent(event);
                if(internalObject != null) {
                    internalObject.getTransform().setPosition(position.x, position.y, position.z);
                }
                scratchMatrix.set(context.getMainScene().getMainCameraRig().getHeadTransform()
                        .getModelMatrix());
                scratchMatrix.transformPosition(position, scratchVector);

                super.setPosition(scratchVector.x, scratchVector.y, scratchVector.z);
                return true;
            }
            return false;
        }

        void updatePosition() {
            if (context.getMainScene() == null) {
                return;
            }
            scratchMatrix.set(context.getMainScene().getMainCameraRig().getHeadTransform()
                    .getModelMatrix());
            scratchMatrix.transformPosition(position, scratchVector);
            super.setPosition(scratchVector.x, scratchVector.y, scratchVector.z);
        }

        @Override
        public void setPosition(float x, float y, float z) {
            position.set(x,y,z);
            if (internalObject != null) {
                internalObject.getTransform().setPosition(x, y, z);
            }
            deviceManager.thread.updatePosition();
        }

        /**
         * formulae for quaternion rotation taken from
         * http://lolengine.net/blog/2014/02/24/quaternion-from-two-vectors-final
         **/
        private void setRotation(Vector3f start, Vector3f end) {
            float norm_u_norm_v = (float) Math.sqrt(start.dot(start)
                    * end.dot(end));
            float real_part = norm_u_norm_v + start.dot(end);
            Vector3f w = new Vector3f();

            if (real_part < 1.e-6f * norm_u_norm_v) {
                /**
                 * If u and v are exactly opposite, rotate 180 degrees around an
                 * arbitrary orthogonal axis. Axis normalisation can happen
                 * later, when we normalise the quaternion.
                 */
                real_part = 0.0f;
                if (Math.abs(start.x) > Math.abs(start.z)) {
                    w = new Vector3f(-start.y, start.x, 0.f);
                } else {
                    w = new Vector3f(0.f, -start.z, start.y);
                }
            } else {
                /** Otherwise, build quaternion the standard way. */
                start.cross(end, w);
            }
            rotation.set(w.x, w.y, w.z, real_part).normalize();
        }
    }

    private class EventHandlerThread extends HandlerThread {
        private static final int MOTION_EVENT = 0;
        private static final int KEY_EVENT = 1;
        private static final int UPDATE_POSITION = 2;
        public static final int SET_ENABLE = 3;
        public static final int SET_SCENE = 4;
        public static final int SEND_INVALIDATE = 5;

        public static final int ENABLE = 0;
        public static final int DISABLE = 1;

        private Handler handler;

        EventHandlerThread(String name) {
            super(name);
        }

        void prepareHandler() {
            handler = new Handler(getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    int id = msg.arg1;
                    switch (msg.what) {
                        case MOTION_EVENT:
                            MotionEvent motionEvent = (MotionEvent) msg.obj;
                            if (dispatchMotionEvent(id, motionEvent) == false) {
                                // recycle if unhandled.
                                motionEvent.recycle();
                            }
                            break;
                        case KEY_EVENT:
                            KeyEvent keyEvent = (KeyEvent) msg.obj;
                            dispatchKeyEvent(id, keyEvent);
                            break;
                        case UPDATE_POSITION:
                            synchronized (controllers) {
                                for (int i = 0; i < controllers.size(); i++) {
                                    GVRMouseController controller = controllers.valueAt(i);
                                    if (controller.isEnabled()) {
                                        controller.updatePosition();
                                    }
                                }
                            }
                            break;
                        case SET_ENABLE:
                            synchronized (controllers) {
                                final GVRMouseController c = controllers.get(id);
                                if (null != c) {
                                    c.callParentSetEnable(msg.arg2 == ENABLE);
                                }
                            }
                            break;

                        case SET_SCENE:
                            synchronized (controllers) {
                                final GVRMouseController c = controllers.get(id);
                                if (null != c) {
                                    c.callParentSetScene((GVRScene) msg.obj);
                                }
                            }
                            break;

                        case SEND_INVALIDATE:
                            synchronized (controllers) {
                                final GVRMouseController c = controllers.get(id);
                                if (null != c) {
                                    c.callParentInvalidate();
                                }
                            }
                            break;

                        default:
                            break;
                    }
                }
            };
        }

        void updatePosition(){
            if(threadStarted){
                Message message = Message.obtain(null, UPDATE_POSITION);
                handler.sendMessage(message);
            }
        }

        boolean submitKeyEvent(int id, KeyEvent event) {
            if (threadStarted) {
                Message message = Message.obtain(null, KEY_EVENT, id, 0, event);
                return handler.sendMessage(message);
            }
            return false;
        }

        boolean submitMotionEvent(int id, MotionEvent event) {
            if (threadStarted) {
                MotionEvent clone = MotionEvent.obtain(event);
                Message message = Message.obtain(null, MOTION_EVENT, id, 0, clone);
                return handler.sendMessage(message);
            }
            return false;
        }

        void setEnable(int id, boolean enable) {
            if (threadStarted) {
                handler.removeMessages(SET_ENABLE);
                Message msg = Message.obtain(handler, SET_ENABLE, id, enable ? ENABLE : DISABLE);
                msg.sendToTarget();
            }
        }

        void setScene(int id, GVRScene scene){
            if (threadStarted) {
                handler.removeMessages(SET_SCENE);
                Message msg = Message.obtain(handler, SET_SCENE, id, 0, scene);
                msg.sendToTarget();
            }
        }

        void sendInvalidate(int id){
            if (threadStarted) {
                handler.removeMessages(SEND_INVALIDATE);
                Message msg = Message.obtain(handler, SEND_INVALIDATE, id, 0);
                msg.sendToTarget();
            }
        }

        private void dispatchKeyEvent(int id, KeyEvent event) {
            if (id != -1) {
                InputDevice device = event.getDevice();
                if (device != null) {
                    GVRMouseController mouseDevice = controllers.get(id);
                    mouseDevice.setKeyEvent(event);
                }
            }
        }

        // The following methods are taken from the controller sample on the
        // Android Developer web site:
        // https://developer.android.com/training/game-controllers/controller-input.html
        private boolean dispatchMotionEvent(int id, MotionEvent event) {
            InputDevice device = event.getDevice();
            if (id == -1 || device == null) {
                return false;
            }

            /*
             * Retrieve the normalized coordinates (-1 to 1) for any given (x,y)
             * value reported by the MotionEvent.
             */
            InputDevice.MotionRange range = device
                    .getMotionRange(MotionEvent.AXIS_X, event.getSource());
            float x = range.getMax() + 1;
            range = event.getDevice().getMotionRange(MotionEvent.AXIS_Y,
                    event.getSource());
            float y = range.getMax() + 1;
            float z;
            x = (event.getX() / x * 2.0f - 1.0f);
            y = 1.0f - event.getY() / y * 2.0f;
            if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                z = (event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0 ? -1 : 1);
            } else {
                z = 0;
            }

            GVRMouseController controller = controllers.get(id);
            return controller.processMouseEvent(x, y, z, event);
        }
    }

    void startThread(){
        if(!threadStarted){
            thread.start();
            thread.prepareHandler();
            threadStarted = true;
            gvrContext.registerDrawFrameListener(this);
        }
    }

    void stopThread() {
        boolean foundEnabled = false;

        for(int i = 0 ;i< controllers.size(); i++){
            GVRCursorController controller = controllers.valueAt(i);
            if(controller.isEnabled()){
                foundEnabled = true;
                break;
            }
        }

        if (!foundEnabled && threadStarted) {
            gvrContext.unregisterDrawFrameListener(this);
            thread.quitSafely();
            thread = new EventHandlerThread(THREAD_NAME);
            threadStarted = false;
        }
    }

    void forceStopThread(){
        if (threadStarted) {
            gvrContext.unregisterDrawFrameListener(this);
            thread.quitSafely();
            thread = new EventHandlerThread(THREAD_NAME);
            threadStarted = false;
        }
    }
}