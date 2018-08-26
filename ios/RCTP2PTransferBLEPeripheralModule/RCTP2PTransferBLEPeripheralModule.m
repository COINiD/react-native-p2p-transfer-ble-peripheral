//
//  RCTP2PTransferBLEPeripheralModule.m
//  RCTP2PTransferBLEPeripheralModule
//
//  Created by Rikard Wissing on 2018-04-01.
//

#import "RCTP2PTransferBLEPeripheralModule.h"
#import <React/RCTBridge.h>
#import <React/RCTConvert.h>
#import <React/RCTEventDispatcher.h>

@implementation RCTP2PTransferBLEPeripheralModule

RCT_EXPORT_MODULE();

- (instancetype)init
{
  if (self = [super init]) {
    NSLog(@"RCTP2PTransferBLEPeripheralModule created");
    _callbacks = [NSMutableDictionary dictionary];
    _addedServices = [NSMutableDictionary dictionary];
    _publishedServices = [NSMutableDictionary dictionary];
    _pausedTransfers = [NSMutableArray new];
    _subscribedCentrals = [NSMutableDictionary dictionary];
  }
  
  return self;
}

+(BOOL)requiresMainQueueSetup
{
  return YES;
}

/* Exported Methods */
RCT_EXPORT_METHOD(isEnabled:(nonnull RCTResponseSenderBlock)callback)
{
  if([_manager state] == CBManagerStatePoweredOn) {
    callback(@[@true]);
  }
  else {
    callback(@[@false]);
  }
}

RCT_EXPORT_METHOD(isSupported:(nonnull RCTResponseSenderBlock)callback)
{
  if ([CLLocationManager isMonitoringAvailableForClass:[CLBeaconRegion class]]){
    callback(@[@true]);
  }
  else {
    callback(@[@false]);
  }
}

RCT_EXPORT_METHOD(start:(nonnull RCTResponseSenderBlock)callback)
{
  if(_manager) {
    if([_manager state] == CBManagerStatePoweredOn) {
      callback(@[@true]);
      return;
    }
    else {
      callback(@[@false]);
      return ;
    }
  }
    
  _manager = [[CBPeripheralManager alloc] initWithDelegate:self queue:dispatch_get_main_queue()];
  [_callbacks setObject:callback forKey:@"startCB"];
}

RCT_EXPORT_METHOD(publishService:(NSString *)serviceUUID callback:(nonnull RCTResponseSenderBlock)callback)
{
  CBMutableService *service = [_addedServices objectForKey:serviceUUID];

  if(service) {
    [_callbacks setObject:callback forKey:@"publishServiceCB"];
    [_manager addService:service]; // really publishService...
  }
}

RCT_EXPORT_METHOD(unpublishService:(NSString *)serviceUUID callback:(nonnull RCTResponseSenderBlock)callback)
{
  CBMutableService *service = [_publishedServices objectForKey:serviceUUID];

  if(service) {
    [_manager removeService:service];  // really unpublishService...

    [_publishedServices removeObjectForKey:serviceUUID];
    [_addedServices removeObjectForKey:serviceUUID];

    callback(@[serviceUUID]);
  }
}

RCT_EXPORT_METHOD(addService:(NSString *)serviceUUID callback:(nonnull RCTResponseSenderBlock)callback)
{
  CBUUID *cbUUID = [CBUUID UUIDWithString:serviceUUID];

  if(cbUUID) {
    CBMutableService *service = [[CBMutableService alloc] initWithType: cbUUID primary: true];
      
    [_addedServices setObject:service forKey:serviceUUID];
    callback(@[service.UUID.UUIDString]);
  }
}

RCT_EXPORT_METHOD(addCharacteristic:(NSString *)serviceUUID characteristicUUID: (NSString *)characteristicUUID callback:(nonnull RCTResponseSenderBlock)callback)
{
  CBMutableService *service = [_addedServices objectForKey:serviceUUID];
 
  CBUUID *cbUUID = [CBUUID UUIDWithString:characteristicUUID];
  
  if(cbUUID) {
    CBMutableCharacteristic *characteristic = [[CBMutableCharacteristic alloc]
      initWithType: cbUUID
      properties: CBCharacteristicPropertyRead+CBCharacteristicPropertyWrite+CBCharacteristicPropertyNotify
      value: nil
      permissions: CBAttributePermissionsReadable+CBAttributePermissionsWriteable
    ];

    NSMutableArray *newCharacteristics = [NSMutableArray arrayWithArray:service.characteristics];
    [newCharacteristics addObject:characteristic];

    service.characteristics = newCharacteristics;

    [_addedServices setObject:service forKey:serviceUUID];

    callback(@[characteristicUUID]);
  }
}

RCT_EXPORT_METHOD(updateValue: (NSString *)value centralUUID:(NSString *)centralUUID serviceUUID:(NSString *)serviceUUID characteristicUUID:(NSString *)characteristicUUID callback:(nonnull RCTResponseSenderBlock)callback) {
  CBMutableCharacteristic *characteristic = [self findCharacteristic: serviceUUID characteristicUUID: characteristicUUID];

  if(!characteristic || ![_sendCharacteristicUUID isEqualToString:characteristic.UUID.UUIDString]) {
    return ;
  }

  CBCentral *central = [self findCentral: centralUUID];

  [self sendValueInChunks:
    [value dataUsingEncoding:NSUTF8StringEncoding]
    forCharacteristic:characteristic
    onSubscribedCentrals:central ? @[central] : nil
    progress:0
    callback: callback
  ];
}

RCT_EXPORT_METHOD(setSendCharacteristic:(NSString *)characteristicUUID)
{
  _sendCharacteristicUUID = characteristicUUID;
}

RCT_EXPORT_METHOD(setReceiveCharacteristic:(NSString *)characteristicUUID)
{
  _receiveCharacteristicUUID = characteristicUUID;
}

- (NSData *)getDataChunk:(NSData *)string size:(NSInteger)size num:(NSInteger)num {
  NSInteger start = size * num;

  if(start >= string.length) {
    return nil;
  }

  if(start+size > string.length) {
    size = string.length-start;
  }

  return [string subdataWithRange: NSMakeRange(start, size)];
}

- (void)sendValueInChunks:(NSData *)value forCharacteristic:(CBMutableCharacteristic *)forCharacteristic onSubscribedCentrals:(NSArray*)onSubscribedCentrals progress:(NSInteger)progress callback:(nonnull RCTResponseSenderBlock)callback{
  UInt32 size = [value length];
  NSData *startPayload = [NSData dataWithBytes:&size length:sizeof(size)];
  NSInteger chunkSize = [[onSubscribedCentrals firstObject] maximumUpdateValueLength]; // checks first central what its maximumupdatevalue is
  
  for(NSInteger i = progress; 1; i++) {
      NSData *chunk = i ? [self getDataChunk: value size: chunkSize num: i-1] : startPayload;
    
    if(chunk != nil && [chunk length]) {
      if([_manager updateValue:chunk forCharacteristic:forCharacteristic onSubscribedCentrals: onSubscribedCentrals] == NO) {
        NSMutableDictionary *pausedData = [NSMutableDictionary new];
        pausedData[@"forCharacteristic"] = forCharacteristic;
        pausedData[@"onSubscribedCentrals"] = onSubscribedCentrals;
        pausedData[@"value"] = value;
        pausedData[@"progress"] = [NSNumber numberWithInteger: i];
        pausedData[@"callback"] = callback;
        [_pausedTransfers addObject:pausedData];
        return ;
      }
      else {
        if(i) {
          [self sendEventWithName:@"sendingProgress" body:@{
            @"receivedBytes": [[NSNumber alloc] initWithUnsignedInteger:((i-1)*chunkSize + [chunk length])],
            @"finalBytes": [[NSNumber alloc] initWithUnsignedInteger:[value length]],
          }];
        }
      }
    }
    else {
      // add callback for when we get a write request from central to let us know that transfer is complete 
      [_callbacks setObject:callback forKey:@"didFinishSendCB"];
      return ;
    }
  }
}

// maximumUpdateValueLength
RCT_EXPORT_METHOD(startAdvertising:(NSString *)name callback:(nonnull RCTResponseSenderBlock)callback)
{
  [_callbacks setObject:callback forKey:@"startAdvertisingCB"];
    
  NSMutableDictionary *advertisementData = [NSMutableDictionary new];
  advertisementData[CBAdvertisementDataLocalNameKey] = name;
  
  NSMutableArray* UUIDArray = [NSMutableArray new];
  for (NSString* uuid in _publishedServices) {
      CBService *service = [_publishedServices objectForKey:uuid];
      [UUIDArray addObject:service.UUID];
  }
  advertisementData[CBAdvertisementDataServiceUUIDsKey] = UUIDArray;
  
  [_manager startAdvertising:advertisementData];
}

RCT_EXPORT_METHOD(stopAdvertising: (nonnull RCTResponseSenderBlock)callback)
{
  [_manager stopAdvertising];
  callback(@[]);
}

/* Events */

- (void)peripheralManager:(CBPeripheralManager *)manager didAddService:(CBService *)service error:(NSError *)error {
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"publishServiceCB"];
  [_publishedServices setObject:service forKey:service.UUID.UUIDString];
  
  if (callback) {
    callback(@[service.UUID.UUIDString]);
    [_callbacks removeObjectForKey:@"publishServiceCB"];
  }
}

- (void)peripheralManagerDidStartAdvertising:(CBPeripheralManager *)peripheral error:(NSError *)error {
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"startAdvertisingCB"];

  if (callback) {
    callback(@[]);
    [_callbacks removeObjectForKey:@"startAdvertisingCB"];
  }
}

- (NSArray<NSString *> *)supportedEvents
{
    return @[
        @"didUpdateState",
        @"didSubscribeToCharacteristic",
        @"didUnsubscribeFromCharacteristic",
        @"readyToUpdateSubscribers",
        @"didReceiveReadRequest",
        @"transferStarted",
        @"transferProgress",
        @"transferDone",
        @"sendingProgress",
        @"sendingDone",
    ];
}

- (void)peripheralManagerDidUpdateState:(CBPeripheralManager *) manager {
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"startCB"];

  if([manager state] == CBManagerStatePoweredOn) {
    if (callback) {
      [_callbacks removeObjectForKey:@"startCB"];
      callback(@[@true]);
    }
  }
  else {
    if (callback) {
      [_callbacks removeObjectForKey:@"startCB"];
      callback(@[@false]);
    }
  }

  [self sendEventWithName:@"didUpdateState" body:[self NSStringForCBManagerState:[manager state]]];
}

- (void)peripheralManager:(CBPeripheralManager *)manager central:(CBCentral *)central didSubscribeToCharacteristic:(CBCharacteristic *)characteristic {
  [_subscribedCentrals setObject:central forKey:central.identifier.UUIDString];

  [self sendEventWithName:@"didSubscribeToCharacteristic"
    body:@{
      @"centralUUID": central.identifier.UUIDString,
      @"serviceUUID": characteristic.service.UUID.UUIDString,
      @"characteristicUUID": characteristic.UUID.UUIDString,
    }
  ];
}

- (void)peripheralManager:(CBPeripheralManager *)manager central:(CBCentral *)central didUnsubscribeFromCharacteristic:(CBCharacteristic *)characteristic {
  [_subscribedCentrals removeObjectForKey:central.identifier.UUIDString];
  [self sendEventWithName:@"didUnsubscribeFromCharacteristic"
    body:@{
      @"centralUUID": central.identifier.UUIDString,
      @"serviceUUID": characteristic.service.UUID.UUIDString,
      @"characteristicUUID": characteristic.UUID.UUIDString,
    }
  ];
}

- (void)peripheralManagerIsReadyToUpdateSubscribers:(CBPeripheralManager *)manager {
  [self sendEventWithName:@"readyToUpdateSubscribers" body:@"readytoupdate"];

  NSMutableArray *newArray = [[NSMutableArray alloc] initWithArray:_pausedTransfers copyItems:YES];
  [_pausedTransfers removeAllObjects];

  for (NSMutableDictionary *pausedData in newArray) {
    [self sendValueInChunks:
      pausedData[@"value"]
      forCharacteristic:pausedData[@"forCharacteristic"]
      onSubscribedCentrals:pausedData[@"onSubscribedCentrals"] 
      progress:[pausedData[@"progress"] integerValue] 
      callback:pausedData[@"callback"]
    ];
  }
}

- (void)peripheralManager:(CBPeripheralManager *)manager didReceiveReadRequest:(CBATTRequest *)request {
  [self sendEventWithName:@"didReceiveReadRequest" body:@"read"];
}

- (void)peripheralManager:(CBPeripheralManager *)manager didReceiveWriteRequests:(NSArray<CBATTRequest *> *)requests {
  [manager respondToRequest:[requests firstObject] withResult:CBATTErrorSuccess];

  for (CBATTRequest *request in requests) {

    // if write is made on sendcharacteristic (= means it is a confirmation that the send is completed)
    if(_sendCharacteristicUUID && [_sendCharacteristicUUID isEqualToString:request.characteristic.UUID.UUIDString]) {
      RCTResponseSenderBlock finishedSendCallback = [_callbacks objectForKey:@"didFinishSendCB"];
      if(finishedSendCallback) {  
        [_callbacks removeObjectForKey:@"didFinishSendCB"];
        [self sendEventWithName:@"sendingDone" body:@"finished"];
        finishedSendCallback(@[]);
      }
    }

    // if write is made on receivecharacteristic (= means it is a write we are waiting for)
    if(_receiveCharacteristicUUID && [_receiveCharacteristicUUID isEqualToString:request.characteristic.UUID.UUIDString]) {
      NSMutableDictionary *retObject = [NSMutableDictionary new];
      retObject[@"centralUUID"] = request.central.identifier.UUIDString;
      retObject[@"serviceUUID"] = request.characteristic.service.UUID.UUIDString;
      retObject[@"characteristicUUID"] = request.characteristic.UUID.UUIDString;

      if(_finalBytes == nil) {
        UInt32 size;
        [request.value getBytes:&size length:sizeof(size)];

        _finalBytes = [[NSNumber alloc] initWithUnsignedInteger:size];
        _receivedData = [[NSMutableData alloc] initWithLength:0];

        [self sendEventWithName:@"transferStarted" body:retObject];
      }
      else {
        [_receivedData appendData:request.value];
      }

      NSNumber *receivedBytes = [[NSNumber alloc] initWithUnsignedInteger:[_receivedData length]];
      retObject[@"receivedBytes"] = receivedBytes;
      retObject[@"finalBytes"] = _finalBytes;
      [self sendEventWithName:@"transferProgress" body:retObject];

      if([receivedBytes isEqualToNumber:_finalBytes]) {
        NSString *stringFromData = [[NSString alloc] initWithData:_receivedData encoding:NSUTF8StringEncoding];
        retObject[@"value"] = stringFromData;
        [self sendEventWithName:@"transferDone" body:retObject];

        _finalBytes = nil;
        _receivedData = nil;
      }
    }
  }
}

/* Helpers */
- (CBMutableCharacteristic *)findCharacteristic:(NSString*)serviceUUID characteristicUUID:(NSString*)characteristicUUID {
  CBMutableService *service = [_publishedServices objectForKey:serviceUUID];

  if(service) {
    for (CBMutableCharacteristic *characteristic in service.characteristics) {
      if([characteristicUUID isEqualToString: characteristic.UUID.UUIDString]) {
        return characteristic;
      }
    }
  }
  return nil;
}

- (CBMutableService *)findCentral:(NSString*)centralUUID {
  CBMutableService *central = [_subscribedCentrals objectForKey:centralUUID];

  if(central) {
    return central;
  }
  return nil;
}

- (NSString *)NSStringForCBManagerState:(CBManagerState)state
{
  switch (state) {
    case CBManagerStateResetting:
      return @"resetting";
    case CBManagerStateUnsupported:
      return @"unsupported";
    case CBManagerStateUnauthorized:
      return @"unauthorized";
    case CBManagerStatePoweredOff:
      return @"poweredOff";
    case CBManagerStatePoweredOn:
      return @"poweredOn";
    case CBManagerStateUnknown:
    default:
      return @"unknown";
  }
}


@end
