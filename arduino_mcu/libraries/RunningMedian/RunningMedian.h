#ifndef RunningMedian_h
#define RunningMedian_h
//
//    FILE: RunningMedian.h
//  AUTHOR: Rob dot Tillaart at gmail dot com
// PURPOSE: RunningMedian library for Arduino
// VERSION: 0.2.00 - template edition
//     URL: http://arduino.cc/playground/Main/RunningMedian
// HISTORY: 0.2.00 first template version by Ronny
//          0.2.01 added getAverage(uint8_t nMedians, float val)
//
// Released to the public domain
//

#include <inttypes.h>

template <typename T, int N> class RunningMedian {

public:

    enum STATUS {OK = 0, NOK = 1};

    RunningMedian() {
        _size = N;
        clear();
    };

    void clear() {
        _cnt = 0;
        _idx = 0;
    };

    void add(T value) {
        _ar[_idx++] = value;
        if (_idx >= _size) _idx = 0; // wrap around
        if (_cnt < _size) _cnt++;
    };

    STATUS getMedian(T& value) {
        if (_cnt > 0) {
            sort();
            value = _as[_cnt/2];
            return OK;
        }
        return NOK;
    };

    STATUS getAverage(float &value) {
        if (_cnt > 0) {
            float sum = 0;
            for (uint8_t i=0; i< _cnt; i++) sum += _ar[i];
            value = sum / _cnt;
            return OK;
        }
        return NOK;
    };

    STATUS getAverage(uint8_t nMedians, float &value) {
        if ((_cnt > 0) && (nMedians > 0))
        {
            if (_cnt < nMedians) nMedians = _cnt;     // when filling the array for first time
            uint8_t start = ((_cnt - nMedians)/2);
            uint8_t stop = start + nMedians;
            sort();
            float sum = 0;
            for (uint8_t i = start; i < stop; i++) sum += _as[i];
            value = sum / nMedians;
            return OK;
        }
        return NOK;
    }

    STATUS getHighest(T& value) {
        if (_cnt > 0) {
            sort();
            value = _as[_cnt-1];
            return OK;
        }
        return NOK;
    };

    STATUS getLowest(T& value) {
        if (_cnt > 0) {
            sort();
            value =  _as[0];
            return OK;
        }
        return NOK;
    };

    unsigned getSize() {
        return _size;
    };

    unsigned getCount() {
        return _cnt;
    }

    STATUS getStatus() {
        return (_cnt > 0 ? OK : NOK);
    };

private:
    uint8_t _size;
    uint8_t _cnt;
    uint8_t _idx;
    T _ar[N];
    T _as[N];
    void sort() {
        // copy
        for (uint8_t i=0; i< _cnt; i++) _as[i] = _ar[i];

        // sort all
        for (uint8_t i=0; i< _cnt-1; i++) {
            uint8_t m = i;
            for (uint8_t j=i+1; j< _cnt; j++) {
                if (_as[j] < _as[m]) m = j;
            }
            if (m != i) {
                T t = _as[m];
                _as[m] = _as[i];
                _as[i] = t;
            }
        }
    };
};

#endif
// --- END OF FILE ---
