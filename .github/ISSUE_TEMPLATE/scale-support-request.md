---
name: Scale support request
about: Scale support request for openScale
title: ''
labels: scale support
assignees: ''

---

**Scale name**
_Write down the scale name and add a link to the vendors app and the product page_

_To support a new scale it is necessary to gather some information._

**Step 1: Read the general reverse engineer process**

- [ ] I have read the [how to reverse engineer a Bluetooth 4.x wiki](https://github.com/oliexdev/openScale/wiki/How-to-reverse-engineer-a-Bluetooth-4.x-scale)  entry

**Step 2: Acquiring some Bluetooth traffic**
_Attach 3 log files with the corresponding true values, read [here](https://github.com/oliexdev/openScale/wiki/How-to-reverse-engineer-a-Bluetooth-4.x-scale#1-acquiring-some-bluetooth-traffic) for further information._

1. Bluetooth HCI Snoop log file
user settings in the vendors app:
```
sex (male/female), body height, age, activity level
```
measured true values in the vendors app for the 1. HCI Snoop log file:
```
weight, date, water, muscle , fat etc.
```
_--> Attach the 1. HCI Snoop log file here <--_

2. Bluetooth HCI Snoop log file
user settings in the vendors app:
```
sex (male/female), body height, age, activity level
```
measured true values in the vendors app for the 2. HCI Snoop log file:
```
weight, date, water, muscle , fat etc.
```

_--> Attach the 2. HCI Snoop log file here <--_

3. Bluetooth HCI Snoop log file
user settings in the vendors app:
```
sex (male/female), body height, age, activity level
```
measured true values in the vendors app for the 3. HCI Snoop log file:
```
weight, date, water, muscle , fat etc.
```
_--> Attach the 3. HCI Snoop log file here <--_

**Step 3: Discover Bluetooth services and characteristic**
_Read [here](https://github.com/oliexdev/openScale/wiki/How-to-reverse-engineer-a-Bluetooth-4.x-scale#2-find-out-the-bluetooth-services-and-characteristic) how to create the openScale debug file_

_--> Attach the openScale debug log file here <--_
