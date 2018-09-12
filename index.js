import { NativeModules, NativeEventEmitter } from 'react-native';

import EventEmitter from 'react-native/Libraries/vendor/emitter/EventEmitter';

const blePeripheralModule = NativeModules.P2PTransferBLEPeripheralModule;
const blePeripheralEmitter = new NativeEventEmitter(blePeripheralModule);

class BLEPeripheral extends EventEmitter {
  constructor() {
    super();
  }

  isSupported = () => {
    return new Promise((resolve, reject) => {
      blePeripheralModule.isSupported(isSupported => {
        resolve(isSupported);
      });
    });
  }

  publish = (serviceUUID, characteristicUUID, localName) => {
    return new Promise((resolve, reject) => {
      this.isSupported().then((supported) => {
        if(!supported) {
          return reject("BLE is not supported on your device.");
        }

        blePeripheralModule.start((started) => {
          if(!started) {
            return reject("Unable to start. Make sure bluetooth is enabled.");
          }
          this.unpublish().then(() => {
            console.log("unpublish...");
            blePeripheralModule.addService(serviceUUID, (data) => {
              this.addedServiceUUID = serviceUUID;
              console.log("addService...", data);

              blePeripheralModule.addCharacteristic(serviceUUID, characteristicUUID, (data) => {
                this.addedCharacteristicUUID = characteristicUUID;
                console.log("addCharacteristic...", data);

                blePeripheralModule.publishService(serviceUUID, (data) => {
                  console.log("publishedService...", data);

                  blePeripheralModule.startAdvertising(localName, (data) => {
                    console.log("startAdvertising...", data);
                    resolve(data);
                  });
                });
              });
            });
          });
        });
      });
    });
  }

  stopAdvertising = () => {
    return new Promise((resolve, reject) => { 
      blePeripheralModule.stopAdvertising(() => {
        console.log('stopped advertising...');
        resolve();
      });
    });
  }

  unpublish = () => {
    return new Promise((resolve, reject) => { 
      this.stopAdvertising()
      .then(() => {

        console.log("unpublishing...");

        if(this.addedServiceUUID === undefined) {
          console.log('already unpublished');
          return resolve();
        }

        let tempUUID = this.addedServiceUUID;
        this.addedServiceUUID = undefined;
        this.addedCharacteristicUUID = undefined;
        blePeripheralModule.unpublishService(tempUUID, () => {
          console.log('unpublished');
          return resolve();
        });
      });
    });
  }


  sendData = (value, filter, sendCharacteristicUUID) => {
    return new Promise((resolve, reject) => {
      const {serviceUUID, localName} = filter;
      const characteristicUUID = sendCharacteristicUUID ? sendCharacteristicUUID : '2222'; // Special characteristic for sending data. Central subscribes to this.

      if(!serviceUUID) {
        return reject("serviceUUID required filter");
      }

      blePeripheralModule.setSendCharacteristic(characteristicUUID);

      blePeripheralEmitter.removeAllListeners('didSubscribeToCharacteristic');
      blePeripheralEmitter.addListener('didSubscribeToCharacteristic', (subscriber) => {
        console.log('didSubscribeToCharacteristic', subscriber, serviceUUID, characteristicUUID);

        if(subscriber.serviceUUID.toLowerCase() !== serviceUUID.toLowerCase() ||
           subscriber.characteristicUUID.toLowerCase() !== characteristicUUID.toLowerCase()) {
          return ;
        }

        blePeripheralEmitter.removeAllListeners('sendingProgress');
        blePeripheralEmitter.removeAllListeners('didSubscribeToCharacteristic');

      
        console.log('updateValue...');

        blePeripheralEmitter.addListener('sendingProgress', (data) => {
          this.emit('sendingProgress', data);
        });

        this.emit('sendingStarted');
        blePeripheralModule.updateValue(value, subscriber.centralUUID, serviceUUID, characteristicUUID, () => {
          setTimeout(() => {
            this.unpublish()
            .then(() => {
              this.emit('sendingDone');
              return resolve();
            });
          }, 1000)
        });
      });

      this.publish(serviceUUID, characteristicUUID, localName)
      .then(() => {})
      .catch((err) => { reject(err); });
   });
  }

  receiveData = (filter, receiveCharacteristicUUID) => {
    return new Promise((resolve, reject) => {
      const {serviceUUID, localName} = filter;
      const characteristicUUID = receiveCharacteristicUUID ? receiveCharacteristicUUID : '3333'; // Special characteristic for receiving data. Central starts sending once Central subscribes.

      if(!serviceUUID) {
        return reject("serviceUUID required filter");
      }

      blePeripheralModule.setReceiveCharacteristic(characteristicUUID);

      console.log('add listeners');

      blePeripheralEmitter.removeAllListeners('transferStarted');
      blePeripheralEmitter.removeAllListeners('transferProgress');
      blePeripheralEmitter.removeAllListeners('transferDone');
      
      blePeripheralEmitter.addListener('transferStarted', (data) => {
        blePeripheralEmitter.removeAllListeners('transferStarted');
        this.emit('receivingStarted', data);
      });

      blePeripheralEmitter.addListener('transferProgress', (data) => {
        this.emit('receivingProgress', data);
      });

      blePeripheralEmitter.addListener('transferDone', (data) => {
        blePeripheralEmitter.removeAllListeners('transferDone');
        blePeripheralEmitter.removeAllListeners('transferProgress');

        this.unpublish().then(() => {
          this.emit('receivingDone', data);
          return resolve(data);
        });
      });

      this.publish(serviceUUID, characteristicUUID, localName)
      .then(() => {})
      .catch((err) => { reject(err); });
    });
  }
}

module.exports = new BLEPeripheral();
