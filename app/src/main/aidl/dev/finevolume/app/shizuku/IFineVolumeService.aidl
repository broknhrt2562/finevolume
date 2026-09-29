package dev.finevolume.app.shizuku;

interface IFineVolumeService {
    void applySessionGain(int streamType, float gain);
    int getActiveSessionCount(int streamType);
    void applyAppGain(String packageName, float gain);
    List<String> getActiveAppPackages();
    float getAppGain(String packageName);
    void destroy();
}
