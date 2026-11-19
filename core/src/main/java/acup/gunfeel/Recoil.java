package acup.gunfeel;

// one recoil movement over time: a Spring, or a hand-made Curve of "t:v" points
public interface Recoil {
    float at(float t);

    float length();
}
