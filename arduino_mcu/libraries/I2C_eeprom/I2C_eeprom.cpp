//
//    FILE: I2C_eeprom.cpp
//  AUTHOR: Rob Tillaart
// VERSION: 1.0.05
// PURPOSE: Simple I2C_eeprom library for Arduino with EEPROM 24LC256 et al.
//
// HISTORY:
// 0.1.00 - 2011-01-21 initial version
// 0.1.01 - 2011-02-07 added setBlock function
// 0.2.00 - 2011-02-11 fixed 64 bit boundary bug
// 0.2.01 - 2011-08-13 _readBlock made more robust + return value
// 1.0.00 - 2013-06-09 support for Arduino 1.0.x
// 1.0.01 - 2013-11-01 fixed writeBlock bug, refactor
// 1.0.02 - 2013-11-03 optimize internal buffers, refactor
// 1.0.03 - 2013-11-03 refactor 5 millis() write-latency
// 1.0.04 - 2013-11-03 fix bug in readBlock, moved waitEEReady() -> more efficient.
// 1.0.05 - 2013-11-06 improved waitEEReady(), added determineSize()
//
// Released to the public domain
//

#include <I2C_eeprom.h>

I2C_eeprom::I2C_eeprom(uint8_t device)
{
    _deviceAddress = device;
    Wire.begin();
    _lastWrite = 0;
    TWBR = 12;          // 12=400Khz  32=200  72=100 152=50    F_CPU/16+(2*TWBR)
}

int I2C_eeprom::writeByte(uint16_t address, uint8_t data)
{
    int rv = _WriteBlock(address, &data, 1);
    return rv;
}

int I2C_eeprom::setBlock(uint16_t address, uint8_t data, uint16_t length)
{
    uint8_t buffer[I2C_TWIBUFFERSIZE];
    for (uint8_t i =0; i< I2C_TWIBUFFERSIZE; i++) buffer[i] = data;

    int rv = _pageBlock(address, buffer, length, false); // todo check return value..
    return rv;
}

int I2C_eeprom::writeBlock(uint16_t address, uint8_t* buffer, uint16_t length)
{
    int rv = _pageBlock(address, buffer, length, true); // todo check return value..
    return rv;
}

uint8_t I2C_eeprom::readByte(uint16_t address)
{
    uint8_t rdata;
    _ReadBlock(address, &rdata, 1);
    return rdata;
}

uint16_t I2C_eeprom::readBlock(uint16_t address, uint8_t* buffer, uint16_t length)
{
    uint16_t rv = 0;
    while (length > 0)
    {
        uint8_t cnt = min(length, I2C_TWIBUFFERSIZE);
        rv += _ReadBlock(address, buffer, cnt);
        address += cnt;
        buffer += cnt;
        length -= cnt;
    }
    return rv;
}

#ifdef I2C_EEPROM_EXTENDED
// returns 64, 32, 16, 8, 4, 2, 1, 0
// 0 is smaller than 1K
uint8_t I2C_eeprom::determineSize()
{
    uint8_t rv = 0;  // unknown
    uint8_t orgValues[8];
    uint16_t addr;

    // remember old values, non destructive
    for (uint8_t i=0; i<8; i++)
    {
        addr = (512 << i) + 1;
        orgValues[i] = readByte(addr);
    }

    // scan page folding
    for (uint8_t i=0; i<8; i++)
    {
        rv = i;
        uint16_t addr1 = (512 << i) + 1;
        uint16_t addr2 = (512 << (i+1)) + 1;
        writeByte(addr1, 0xAA);
        writeByte(addr2, 0x55);
        if (readByte(addr1) == 0x55) // folded!
        {
            break;
        }
    }

    // restore original values
    for (uint8_t i=0; i<8; i++)
    {
        uint16_t addr = (512 << i) + 1;
        writeByte(addr, orgValues[i]);
    }
    return 0x01 << (rv-1);
}
#endif

////////////////////////////////////////////////////////////////////
//
// PRIVATE
//

// _pageBlock aligns buffer to page boundaries for writing.
// and to TWI buffer size
// returns 0 = OK otherwise error
int I2C_eeprom::_pageBlock(uint16_t address, uint8_t* buffer, uint16_t length, bool incrBuffer)
{
    int rv = 0;
    while (length > 0)
    {
        uint8_t bytesUntilPageBoundary = I2C_EEPROM_PAGESIZE - address%I2C_EEPROM_PAGESIZE;
        uint8_t cnt = min(length, bytesUntilPageBoundary);
        cnt = min(cnt, I2C_TWIBUFFERSIZE);

        int rv = _WriteBlock(address, buffer, cnt); // todo check return value..
        if (rv != 0) return rv;

        address += cnt;
        if (incrBuffer) buffer += cnt;
        length -= cnt;
    }
    return rv;
}

// pre: length <= I2C_EEPROM_PAGESIZE  && length <= I2C_TWIBUFFERSIZE;
// returns 0 = OK otherwise error
int I2C_eeprom::_WriteBlock(uint16_t address, uint8_t* buffer, uint8_t length)
{
    waitEEReady();

    Wire.beginTransmission(_deviceAddress);
#if defined(ARDUINO) && ARDUINO >= 100
    Wire.write((int)(address >> 8));
    Wire.write((int)(address & 0xFF));
    for (uint8_t cnt = 0; cnt < length; cnt++)
    Wire.write(buffer[cnt]);
#else
    Wire.send((int)(address >> 8));
    Wire.send((int)(address & 0xFF));
    for (uint8_t cnt = 0; cnt < length; cnt++)
    Wire.send(buffer[cnt]);
#endif
    int rv = Wire.endTransmission();
    _lastWrite = micros();
    return rv;
}

// pre: buffer is large enough to hold length bytes
// returns bytes written
uint8_t I2C_eeprom::_ReadBlock(uint16_t address, uint8_t* buffer, uint8_t length)
{
    waitEEReady();

    Wire.beginTransmission(_deviceAddress);
#if defined(ARDUINO) && ARDUINO >= 100
    Wire.write((int)(address >> 8));
    Wire.write((int)(address & 0xFF));
#else
    Wire.send((int)(address >> 8));
    Wire.send((int)(address & 0xFF));
#endif
    int rv = Wire.endTransmission();
    if (rv != 0) return 0;  // error

    Wire.requestFrom(_deviceAddress, length);
    uint8_t cnt = 0;
    uint32_t before = millis();
    while ((cnt < length) && ((millis() - before) < I2C_EEPROM_TIMEOUT))
    {
#if defined(ARDUINO) && ARDUINO >= 100
        if (Wire.available()) buffer[cnt++] = Wire.read();
#else
        if (Wire.available()) buffer[cnt++] = Wire.receive();
#endif
    }
    return cnt;
}

void I2C_eeprom::waitEEReady()
{
#define I2C_WRITEDELAY  5000

    // Wait until EEPROM gives ACK again.
    // this is a bit faster than the hardcoded 5 milli
    while ((micros() - _lastWrite) <= I2C_WRITEDELAY)
    {
        Wire.beginTransmission(_deviceAddress);
        int x = Wire.endTransmission();
        if (x == 0) break;
    }
}

//
// END OF FILE
//

