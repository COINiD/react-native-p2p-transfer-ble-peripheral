package org.coinid.rctp2ptransferperipheral;

import android.annotation.TargetApi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;

/* Advertising (Peripheral) */
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;

/* React Native */
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.content.Context;
import android.content.pm.PackageManager;
import android.support.annotation.Nullable;
import android.os.ParcelUuid;

import android.util.Log;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

import android.os.Build;
import android.content.Intent;

public class RCTP2PTransferBLEPeripheralModule extends ReactContextBaseJavaModule {

  private static final int REQUEST_ENABLE_BT = 1;
  private ReactApplicationContext mContext;

  private BluetoothManager mManager;
  private BluetoothAdapter mAdapter;
  private BluetoothLeAdvertiser mLeAdvertiser;
  public BluetoothGattServer mGattServer;
  private BluetoothGattService mGattService;
  private BluetoothGattCharacteristic mGattCharacteristic;
  private BluetoothDevice mCentral;

  private String mSendCharacteristicUUID;
  private String mReceiveCharacteristicUUID;
  private int mConnectionMtu;

  private Callback mDidFinishSendCB;

  private UUID DESCRIPTOR_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  private int mFinalBytes;
  private int mReceivedBytes;
  private ByteBuffer mReceivedData;

  private BluetoothGattServerCallback mGattServerCallback;
  private AdvertiseCallback mAdvertiseCallback;
  
  public RCTP2PTransferBLEPeripheralModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.mContext = reactContext;
    this.init();
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private void setupBLECallbacks() {
    this.mAdvertiseCallback = new AdvertiseCallback() {
      @Override
      public void onStartSuccess(AdvertiseSettings settingsInEffect) {
        super.onStartSuccess(settingsInEffect);
      }
   
      @Override
      public void onStartFailure(int errorCode) {
        super.onStartFailure(errorCode);
      }
    };

    this.mGattServerCallback = new BluetoothGattServerCallback() {
      @Override
      public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        Log.d("GattServer", "Our gatt server connection state changed, new state ");
        Log.d("GattServer", Integer.toString(newState));
        super.onConnectionStateChange(device, status, newState);

        WritableMap params = Arguments.createMap();
        params.putString("name", "dooley-doo");
        sendEvent("onConnectionStateChange", params);
      }

      @Override
      public void onServiceAdded(int status, BluetoothGattService service) {
        Log.d("GattServer", "Our gatt server service was added.");
        super.onServiceAdded(status, service);

        WritableMap params = Arguments.createMap();
        params.putString("name", "dooley-doo");
        sendEvent("onServiceAdded", params);
      }

      @Override
      public void onMtuChanged(BluetoothDevice device, int mtu) {
        super.onMtuChanged(device, mtu);

        WritableMap params = Arguments.createMap();
        params.putInt("mtu", mtu);
        sendEvent("onMtuChanged", params);

        mConnectionMtu = mtu;
      }

      @Override
      public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
        Log.d("GattServer", "Our gatt characteristic was read.");
        super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
        mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());

        WritableMap params = Arguments.createMap();
        params.putString("name", "dooley-doo");
        sendEvent("onCharacteristicReadRequest", params);
      }

      @Override
      public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);

        WritableMap params = Arguments.createMap();
        params.putString("characteristic", mSendCharacteristicUUID);
        sendEvent("onCharacteristicWriteRequest", params);

        // if write is made on sendcharacteristic (= means it is a confirmation that the send is completed)
        if(mSendCharacteristicUUID != null && mSendCharacteristicUUID.equals(characteristic.getUuid().toString()) ) {
          if(mDidFinishSendCB != null) {
            mDidFinishSendCB.invoke();
            mDidFinishSendCB = null;
          }
        }

        // if write is made on receivecharacteristic (= means it is a write we are waiting for)
        if(mReceiveCharacteristicUUID != null && mReceiveCharacteristicUUID.equals(characteristic.getUuid().toString()) ) {
          String centralUUID = device.toString();
          String serviceUUID = characteristic.getService().getUuid().toString();
          String characteristicUUID = characteristic.getUuid().toString();

          if(mFinalBytes == 0) {
            mFinalBytes = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
            mReceivedData = ByteBuffer.allocate(mFinalBytes);

            WritableMap retObject = Arguments.createMap();
            retObject.putString("centralUUID", centralUUID);
            retObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
            retObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
            retObject.putInt("mFinalBytes", mFinalBytes);

            sendEvent("transferStarted", retObject);
          }
          else {
            mReceivedData.put(value);
            mReceivedBytes += value.length;
          }

          WritableMap progressRetObject = Arguments.createMap();
          progressRetObject.putString("centralUUID", centralUUID);
          progressRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
          progressRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
          progressRetObject.putInt("receivedBytes", mReceivedBytes);
          progressRetObject.putInt("finalBytes", mFinalBytes);

          sendEvent("transferProgress", progressRetObject);

          if(mReceivedBytes == mFinalBytes) {
            String stringFromData = new String( mReceivedData.array(), Charset.forName("UTF-8") );

            WritableMap doneRetObject = Arguments.createMap();
            doneRetObject.putString("centralUUID", centralUUID);
            doneRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
            doneRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
            doneRetObject.putInt("receivedBytes", mReceivedBytes);
            doneRetObject.putInt("finalBytes", mFinalBytes);
            doneRetObject.putString("value", stringFromData);

            sendEvent("transferDone", doneRetObject);

            mFinalBytes = 0;
            mReceivedData = null;
            mReceivedBytes = 0;
          }
        }

        if (responseNeeded) {
          mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
      }

      @Override
      public void onNotificationSent(BluetoothDevice device, int status) {
        Log.d("GattServer", "onNotificationSent");
        super.onNotificationSent(device, status);

        WritableMap params = Arguments.createMap();
        params.putString("name", "dooley-doo");
        sendEvent("onNotificationSent", params);
      }

      @Override
      public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
        Log.d("GattServer", "Our gatt server descriptor was read.");
        super.onDescriptorReadRequest(device, requestId, offset, descriptor);

        WritableMap params = Arguments.createMap();
        params.putString("name", "dooley-doo");
        sendEvent("onDescriptorReadRequest", params);
      }

      @Override
      public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);

        WritableMap params = Arguments.createMap();
        params.putString("configuuid", DESCRIPTOR_CONFIG_UUID.toString());
        params.putString("descriptor", descriptor.getUuid().toString());

        params.putString("enable notification", BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.toString());
        params.putString("enable indication", BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.toString());
        params.putString("disable notification", BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.toString());
        params.putString("value", value.toString());
        sendEvent("onDescriptorWriteRequest", params);

        if(descriptor.getUuid().equals(DESCRIPTOR_CONFIG_UUID)) {

          String centralUUID = device.toString();
          String serviceUUID = descriptor.getCharacteristic().getService().getUuid().toString();
          String characteristicUUID = descriptor.getCharacteristic().getUuid().toString();

          WritableMap subscriber = Arguments.createMap();
          subscriber.putString("centralUUID", centralUUID);
          subscriber.putString("serviceUUID", removeBaseUUID(serviceUUID));
          subscriber.putString("characteristicUUID", removeBaseUUID(characteristicUUID));

          if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
            mCentral = device;
            sendEvent("didSubscribeToCharacteristic", subscriber);
          }

          if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
            sendEvent("didUnsubscribeFromCharacteristic", subscriber);
          }

          if (responseNeeded) {
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
          }
        } else {

          if (responseNeeded) {
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
          }
        }

      }

      @Override
      public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
        Log.d("GattServer", "Our gatt server on execute write.");
        super.onExecuteWrite(device, requestId, execute);

        WritableMap params = Arguments.createMap();
        params.putString("name", "dooley-doo");
        sendEvent("onExecuteWrite", params);
      }
    };
  }

  public Integer setupModule() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return -1;
    }

    if (null == this.mContext.getSystemService(Context.BLUETOOTH_SERVICE)) {
      return -2;
    }

    if (false == this.mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      return -3;
    }

    if (null == this.mManager) {
      this.mManager = (BluetoothManager) this.mContext.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    if (null == this.mAdapter) {
      this.mAdapter = this.mManager.getAdapter();
    }

    if (null == this.mAdapter) {
      return -4;
    }

    if (true == this.mAdapter.isEnabled() && false == this.mAdapter.isMultipleAdvertisementSupported()){
      return -5;
    }

    return 0;
  }

  public Integer init() {
    if(this.setupModule() != 0) {
      return -1;
    }

    if(this.mGattServerCallback == null) {
      this.setupBLECallbacks();
    }

    this.mConnectionMtu = 23;
    this.mLeAdvertiser = this.mAdapter.getBluetoothLeAdvertiser();
    this.mGattServer = this.mManager.openGattServer(this.mContext, this.mGattServerCallback);
    this.mFinalBytes = 0;
    this.mReceivedData = null;
    this.mReceivedBytes = 0;

    return 0;
  }

  @ReactMethod
  void isSupported(Callback callback) {
    if(this.setupModule() != 0) {
      callback.invoke(this.setupModule());
      return;
    }
    callback.invoke(true);
  }

  @Override
  public String getName() {
    return "P2PTransferBLEPeripheralModule";
  }

  @ReactMethod
  void setSendCharacteristic(String characteristicUUID) {
    this.mSendCharacteristicUUID = getUUIDStringFromSimple(characteristicUUID);
  }

  @ReactMethod
  void setReceiveCharacteristic(String characteristicUUID) {
    this.mReceiveCharacteristicUUID = getUUIDStringFromSimple(characteristicUUID);
  }

  @ReactMethod
  void start(Callback callback) {
    if(this.init() != 0) {
      callback.invoke(false);
      return;
    }

    if (!this.mAdapter.isEnabled()) {
      callback.invoke(false);
      return ;
    }

    callback.invoke(true);
  }

  @ReactMethod
  void unpublishService(String serviceUUID, Callback callback) {
    BluetoothGattService previousService = this.mGattService;

    if(null != previousService) {     
      this.mGattServer.removeService(previousService);
      callback.invoke(true);
      return ;
    }

    callback.invoke(false);
  }

  @ReactMethod
  void startAdvertising(String name, final Callback callback) {
    this.mAdapter.setName(name);

    AdvertiseSettings settings = (new AdvertiseSettings.Builder())
      .setConnectable(true)
      .setAdvertiseMode( AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY )
      .setTxPowerLevel( AdvertiseSettings.ADVERTISE_TX_POWER_HIGH )
      .build();

    ParcelUuid uuid = new ParcelUuid(this.mGattService.getUuid());

    AdvertiseData data = (new AdvertiseData.Builder())
      .setIncludeDeviceName(true)
      .addServiceUuid(uuid)
      .build();

    this.mLeAdvertiser.startAdvertising(settings, data, this.mAdvertiseCallback);
    callback.invoke(true);
  }

  @ReactMethod
  void stopAdvertising(Callback callback) {
    if(this.mLeAdvertiser == null) {
      callback.invoke(false);
      return;
    }
    
    this.mLeAdvertiser.stopAdvertising(this.mAdvertiseCallback);
    callback.invoke(true);
  }

  String getUUIDStringFromSimple(String stringUUID) {
    // base uuid: 0000xxxx-0000-1000-8000-00805F9B34FB
    if(stringUUID.length() == 4) {
      return "0000" + stringUUID + "-0000-1000-8000-00805f9b34fb";
    }
    if(stringUUID.length() == 8) {
      return stringUUID + "-0000-1000-8000-00805f9b34fb";
    }
    return stringUUID;
  }

  String removeBaseUUID(String stringUUID) {
    // base uuid: 0000xxxx-0000-1000-8000-00805F9B34FB
    if(stringUUID.substring(0,4).equals("0000") &&
       stringUUID.substring(8).equals("-0000-1000-8000-00805f9b34fb")) {
      return stringUUID.substring(4,8);
    }
    if(stringUUID.substring(8).equals("-0000-1000-8000-00805f9b34fb")) {
      return stringUUID.substring(0,8);
    }
    return stringUUID;
  }

  @ReactMethod
  void addService(String serviceUUID, Callback callback) {
    serviceUUID = getUUIDStringFromSimple(serviceUUID);

    BluetoothGattService gattService = new BluetoothGattService(
      UUID.fromString(serviceUUID),
      BluetoothGattService.SERVICE_TYPE_PRIMARY
    );

    this.mGattService = gattService;

    callback.invoke(serviceUUID);
  }

  @ReactMethod
  void addCharacteristic(String serviceUUID, String characteristicUUID, Callback callback) {
    characteristicUUID = getUUIDStringFromSimple(characteristicUUID);

    BluetoothGattCharacteristic gattCharacteristic = new BluetoothGattCharacteristic(
      UUID.fromString(characteristicUUID),
      (BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY),
      (BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE)
    );

    // In order to support subsriptions this descriptor is needed
    BluetoothGattDescriptor gattCharacteristicConfig = new BluetoothGattDescriptor(DESCRIPTOR_CONFIG_UUID, (BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE));
    gattCharacteristic.addDescriptor(gattCharacteristicConfig);

    this.mGattCharacteristic = gattCharacteristic;

    this.mGattService.addCharacteristic(gattCharacteristic);
    callback.invoke(characteristicUUID);
  }

  @ReactMethod
  void updateValue(String value, String centralUUID, String serviceUUID, String characteristicUUID, Callback callback) {
    sendValueInChunks(value.getBytes(Charset.forName("UTF-8")), characteristicUUID, centralUUID, 0, callback);
  }

  void sendValueInChunks(byte[] value, String characteristicUUID, String centralUUID, int progress, Callback callback) {
    int size = value.length;
    
    byte[] startPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array();

    int chunkSize = this.mConnectionMtu-3; // 3 bytes is reserved for other data
    
    for(int i = progress; true; i++) {
      byte[] chunk = i > 0 ? getDataChunk(value, chunkSize, i-1) : startPayload;

      if(chunk != null && chunk.length > 0) {
        if(this.mGattCharacteristic.setValue(chunk) &&
          this.mGattServer.notifyCharacteristicChanged(mCentral, mGattCharacteristic, true)) {

          if(i > 0) {
            WritableMap params = Arguments.createMap();
            params.putInt("receivedBytes", (i-1)*chunkSize + chunk.length);
            params.putInt("finalBytes", value.length);
            sendEvent("sendingProgress", params);
          }
        }
        else {
          // ?
        }
      }
      else {
        this.mDidFinishSendCB = callback;
        return ;
      }
    }
  }

  byte[] getDataChunk(byte[] value, int size, int num) {
    int start = size * num;

    if(start >= value.length) {
      return null;
    }

    if(start+size > value.length) {
      size = value.length-start;
    }

    return Arrays.copyOfRange(value, start, start+size);
  }

  private void sendEvent( String eventName, @Nullable WritableMap params ) {
    this.mContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit(eventName, params);
  }

  @ReactMethod
  void publishService(String serviceUUID, Callback callback) {
    this.mGattServer.addService(this.mGattService);
    callback.invoke(serviceUUID);
  }
}