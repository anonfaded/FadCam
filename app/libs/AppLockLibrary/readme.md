# App Lock

[![Download](https://img.shields.io/maven-central/v/com.guardanis/applock)](https://search.maven.org/artifact/com.guardanis/applock)
[![Android Arsenal](https://img.shields.io/badge/Android%20Arsenal-applock-brightgreen.svg?style=flat)](http://android-arsenal.com/details/1/2995)

A simple library for locking and unlocking Activities with a PIN code or Fingerprint (e.g. a child lock). 

![AppLock Sample](https://github.com/mattsilber/applock/raw/master/applock.gif)

### Installation

```groovy
repositories {
    maveCentral()
}

dependencies {
    compile('com.guardanis:applock:3.0.2')
}
```

### Usage

The goal of AppLock is to allow users to enroll and authenticate with a PIN or Fingerprint to lock the application from being used by unauthorized parties. 

##### Activities
To open the Activity to create a PIN, you can simply open the `AppLockActivity` via (PS: If you want to use the dialog instead of the Activity (which looks cooler), see the dialog stuff below) `Intents`:

```java
Intent intent = new Intent(activity, LockCreationActivity.class);
startActivityForResult(intent, LockingHelper.REQUEST_CODE_CREATE_LOCK);
```

To check if the user has elected to enroll in AppLock authentication, you can simply call *AppLock.isEnrolled(Contect)* and redirect to the *UnlockActivity* if the action requires authentication:

```java
Intent intent = new Intent(activity, UnlockActivity.class);
startActivityForResult(intent, AppLock.REQUEST_CODE_ULOCK);    
```

If you want to do both of the above in a single step (that is, check if the user is enrolled and open the unlock flow if true), you can call:

```java
if(!AppLock.unlockIfRequired(Activity))
    doSomethingThatRequiresLockingIfEnabled();

...

@Override
public void onActivityResult(int requestCode, int resultCode, Intent data){
    if(requestCode == LockingHelper.REQUEST_CODE_ULOCK && resultCode == Activity.RESULT_OK)
        doSomethingThatRequiresLockingIfEnabled();
}

```

##### Dialogs

If you want to do the above with a Dialog, instead of an Activity (which looks cooler), you can simply call:

```java
new UnlockDialogBuilder(activity)
    .onUnlocked(() -> { doSomethingThatRequiresLockingIfEnabled(); })
    .onCanceled(() -> { })
    .showIfRequiredOrSuccess(TimeUnit.MINUTES.toMillies(15));
```


Or, create the enrollment with a Dialog:

```java
new LockCreationDialogBuilder(this)
    .onCanceled(() -> { showIndicatorMessage("You canceled..."); })
    .onLockCreated(() -> { showIndicatorMessage("Lock created!"); })
    .show()
```

If you want an Activity to remain fully locked once a PIN has been entered, ensure that you override *onPostResume()* and call *AppLock.onActivityResumed(Activity);* e.g.

```java
@Override
protected void onPostResume(){
    super.onPostResume();
    
    AppLock.onActivityResumed(this);
}
```

or you can simply have your Activity extend the *LockableCompatActivity* supplied with this library.

By default, AppLock considers a successful login as valid for 15 minutes, regardless of application state. You can shorten or extend that length by overriding the integer value for *applock__activity_lock_reenable_minutes* in your resources. Doing so will cause any Activity to re-open the *UnlockActivity* after the delay has passed. If you only want authentication present on a specific action (e.g. payments), you should use the `UnlockDialogBuilder`'s methods posted above instead of locking the entire Activity.

To change the default length of the PIN, you can override

```xml
<integer name="applock__input_pin_item_count">4</integer>
```

### Theme

All themes, styles, dimensions, strings, etc. are all customizable via overriding the resources. See `applock/src/main/res/values/` for details.

### Moved to MavenCentral

As of version 3.0.2, applock will be hosted on MavenCentral. Versions 3.0.1 and below will remain on JCenter.

### TODO:
* Allow backup authentication options