//
//  RCTCONVERT+CBUUID.h
//  RCTP2PTransferBLEPeripheralModule
//
//  Created by Jacob Rosenthal on 9/7/15.
//  Copyright (c) 2015 Facebook. All rights reserved.
//

#ifndef RCTP2PTransferBLEPeripheralModule_RCTCONVERT_CBUUID_h
#define RCTP2PTransferBLEPeripheralModule_RCTCONVERT_CBUUID_h

#import <CoreBluetooth/CoreBluetooth.h>
#import <React/RCTConvert.h>

@interface RCTConvert (CBUUID)

+ (CBUUID *)CBUUID:(id)json;

typedef NSArray CBUUIDArray;
+ (CBUUIDArray *)CBUUIDArray:(id)json;

@end

#endif
