package acup.gunfeel;

import org.joml.Matrix4fc;

public interface GpuPoseAccess {
    Matrix4fc gunfeel$pose();
    long gunfeel$revision();
    void gunfeel$seed(Matrix4fc pose);
}
