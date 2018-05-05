//
//  RCTP2PTransferBLEPeripheralModule.h
//  RCTP2PTransferBLEPeripheralModule
//
//  Created by Rikard Wissing on 2018-04-01.
//

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTP2PTransferBLEPeripheralModule : RCTEventEmitter <RCTBridgeModule, CBPeripheralManagerDelegate>{
    CBPeripheralManager *_manager;
    NSMutableDictionary *_addedServices;
    NSMutableDictionary *_publishedServices;
    NSMutableDictionary *_callbacks;
    NSMutableDictionary *_subscribedCentrals;
    NSMutableArray *_pausedTransfers;
    NSMutableData *_receivedData;
    NSNumber *_finalBytes;
}


@end
