// port/continuum: NTM NEXT's (HBM's Nuclear Tech Mod, 26.2) animation classes, so that a gun on NTM Continuum moves
// as on 26.2 (NextGunAnimations). Taken from NTM NEXT 1.0.3, LGPL-3.0-only: see continuum/third-party/ntm-next.txt.
package acup.gunfeel.next;

public class BusAnimationKeyframe {
    static final double pow_min = 9.765625E-4;
    static final double pow_scale = 1.0009775F;
    public double value = 0.0;
    public BusAnimationKeyframe.IType interpolationType;
    public BusAnimationKeyframe.EType easingType;
    public int originalDuration;
    public int duration;
    public double leftX;
    public double leftY;
    public BusAnimationKeyframe.HType leftType;
    public double rightX;
    public double rightY;
    public BusAnimationKeyframe.HType rightType;
    public double amplitude;
    public double period;
    public double back;

    public BusAnimationKeyframe() {
        this.originalDuration = this.duration = 1;
        this.interpolationType = BusAnimationKeyframe.IType.LINEAR;
        this.easingType = BusAnimationKeyframe.EType.AUTO;
    }

    public BusAnimationKeyframe(double value, int duration) {
        this();
        this.value = value;
        this.originalDuration = this.duration = (int)(duration / Math.max(0.001, dev.golem.core.render.anim.BusAnimationKeyframe.speed));
    }

    public BusAnimationKeyframe(double value, int duration, BusAnimationKeyframe.IType interpolation) {
        this(value, duration);
        this.interpolationType = interpolation;
    }

    public BusAnimationKeyframe(double value, int duration, BusAnimationKeyframe.IType interpolation, BusAnimationKeyframe.EType easing) {
        this(value, duration, interpolation);
        this.easingType = easing;
    }

    public double interpolate(double startTime, double currentTime, BusAnimationKeyframe previous) {
        if (previous == null) {
            previous = new BusAnimationKeyframe();
        }

        double a = this.value;
        double b = previous.value;
        double t = this.time(startTime, currentTime, this.duration);
        double begin = previous.value;
        double change = this.value - previous.value;
        double time = currentTime - startTime;
        if (Math.abs(previous.value - this.value) < 1.0E-6) {
            return this.value;
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.BEZIER) {
            double v1y = previous.value;
            double v2x = previous.rightX;
            double v2y = previous.rightY;
            double v3x = this.leftX;
            double v3y = this.leftY;
            double v4x = startTime + this.duration;
            double v4y = this.value;
            double h1x = startTime - v2x;
            double h1y = v1y - v2y;
            double h2x = v4x - v3x;
            double h2y = v4y - v3y;
            double len = v4x - startTime;
            double len1 = Math.abs(h1x);
            double len2 = Math.abs(h2x);
            if (len1 + len2 != 0.0) {
                if (len1 > len) {
                    double fac = len / len1;
                    v2x = startTime - fac * h1x;
                    v2y = v1y - fac * h1y;
                }

                if (len2 > len) {
                    double fac = len / len2;
                    v3x = v4x - fac * h2x;
                    v3y = v4y - fac * h2y;
                }
            }

            double curveT = this.findZero(currentTime, startTime, v2x, v3x, v4x);
            return this.cubicBezier(v1y, v2y, v3y, v4y, curveT);
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.BACK) {
            switch (previous.easingType) {
                case EASE_IN:
                    return this.BLI_easing_back_ease_in(time, begin, change, this.duration, previous.back);
                case EASE_IN_OUT:
                    return this.BLI_easing_back_ease_in_out(time, begin, change, this.duration, previous.back);
                default:
                    return this.BLI_easing_back_ease_out(time, begin, change, this.duration, previous.back);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.BOUNCE) {
            switch (previous.easingType) {
                case EASE_IN:
                    return this.BLI_easing_bounce_ease_in(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_bounce_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_bounce_ease_out(time, begin, change, this.duration);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.CIRC) {
            switch (previous.easingType) {
                case EASE_OUT:
                    return this.BLI_easing_circ_ease_out(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_circ_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_circ_ease_in(time, begin, change, this.duration);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.CUBIC) {
            switch (previous.easingType) {
                case EASE_OUT:
                    return this.BLI_easing_cubic_ease_out(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_cubic_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_cubic_ease_in(time, begin, change, this.duration);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.ELASTIC) {
            switch (previous.easingType) {
                case EASE_IN:
                    return this.BLI_easing_elastic_ease_in(time, begin, change, this.duration, previous.amplitude, previous.period);
                case EASE_IN_OUT:
                    return this.BLI_easing_elastic_ease_in_out(time, begin, change, this.duration, previous.amplitude, previous.period);
                default:
                    return this.BLI_easing_elastic_ease_out(time, begin, change, this.duration, previous.amplitude, previous.period);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.EXPO) {
            switch (previous.easingType) {
                case EASE_OUT:
                    return this.BLI_easing_expo_ease_out(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_expo_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_expo_ease_in(time, begin, change, this.duration);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.QUAD) {
            switch (previous.easingType) {
                case EASE_OUT:
                    return this.BLI_easing_quad_ease_out(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_quad_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_quad_ease_in(time, begin, change, this.duration);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.QUART) {
            switch (previous.easingType) {
                case EASE_OUT:
                    return this.BLI_easing_quart_ease_out(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_quart_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_quart_ease_in(time, begin, change, this.duration);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.QUINT) {
            switch (previous.easingType) {
                case EASE_OUT:
                    return this.BLI_easing_quint_ease_out(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_quint_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_quint_ease_in(time, begin, change, this.duration);
            }
        } else if (previous.interpolationType == BusAnimationKeyframe.IType.SINE) {
            switch (previous.easingType) {
                case EASE_OUT:
                    return this.BLI_easing_sine_ease_out(time, begin, change, this.duration);
                case EASE_IN_OUT:
                    return this.BLI_easing_sine_ease_in_out(time, begin, change, this.duration);
                default:
                    return this.BLI_easing_sine_ease_in(time, begin, change, this.duration);
            }
        } else {
            return (a - b) * t + b;
        }
    }

    private double sqrt3(double d) {
        if (d > 1.0E-6) {
            return Math.exp(Math.log(d) / 3.0);
        } else {
            return d > -1.0E-6 ? 0.0 : -Math.exp(Math.log(-d) / 3.0);
        }
    }

    private double time(double start, double end, double duration) {
        if (this.interpolationType == BusAnimationKeyframe.IType.SIN_UP) {
            return -Math.sin(((end - start) / duration * Math.PI + Math.PI) / 2.0) + 1.0;
        } else if (this.interpolationType == BusAnimationKeyframe.IType.SIN_DOWN) {
            return Math.sin((end - start) / duration * Math.PI / 2.0);
        } else {
            return this.interpolationType == BusAnimationKeyframe.IType.SIN_FULL
                ? (-Math.cos((end - start) / duration * Math.PI) + 1.0) / 2.0
                : (end - start) / duration;
        }
    }

    private double solveCubic(double c0, double c1, double c2, double c3) {
        if (!(c3 > 1.0E-6) && !(c3 < -1.0E-6)) {
            if (c2 > 1.0E-6) {
                double p = c1 * c1 - 4.0 * c2 * c0;
                if (p > 1.0E-6) {
                    p = Math.sqrt(p);
                    double result = (-c1 - p) / (2.0 * c2);
                    if (result < 1.0E-6 || result > 1.000001) {
                        result = (-c1 + p) / (2.0 * c2);
                    }

                    return result;
                }

                if (p > -1.0E-6) {
                    return -c1 / (2.0 * c2);
                }
            }

            return c1 > 1.0E-6 ? -c0 / c1 : 0.0;
        } else {
            double a = c2 / c3;
            double b = c1 / c3;
            double c = c0 / c3;
            a /= 3.0;
            double px = b / 3.0 - a * a;
            double q = (2.0 * a * a * a - a * b + c) / 2.0;
            double d = q * q + px * px * px;
            if (d > 1.0E-6) {
                double t = Math.sqrt(d);
                return this.sqrt3(-q + t) + this.sqrt3(-q - t) - a;
            } else if (d > -1.0E-6) {
                double t = this.sqrt3(-q);
                double result = 2.0 * t - a;
                if (result < 1.0E-6 || result > 1.000001) {
                    result = -t - a;
                }

                return result;
            } else {
                double phi = Math.acos(-q / Math.sqrt(-(px * px * px)));
                double t = Math.sqrt(-px);
                px = Math.cos(phi / 3.0);
                q = Math.sqrt(3.0 - 3.0 * px * px);
                double result = 2.0 * t * px - a;
                if (result < 1.0E-6 || result > 1.000001) {
                    result = -t * (px + q) - a;
                }

                if (result < 1.0E-6 || result > 1.000001) {
                    result = -t * (px - q) - a;
                }

                return result;
            }
        }
    }

    private double findZero(double t, double x1, double x2, double x3, double x4) {
        double c0 = x1 - t;
        double c1 = 3.0 * (x2 - x1);
        double c2 = 3.0 * (x1 - 2.0 * x2 + x3);
        double c3 = x4 - x1 + 3.0 * (x2 - x3);
        return this.solveCubic(c0, c1, c2, c3);
    }

    private double cubicBezier(double y1, double y2, double y3, double y4, double t) {
        double c1 = 3.0 * (y2 - y1);
        double c2 = 3.0 * (y1 - 2.0 * y2 + y3);
        double c3 = y4 - y1 + 3.0 * (y2 - y3);
        return y1 + t * c1 + t * t * c2 + t * t * t * c3;
    }

    double BLI_easing_back_ease_in(double time, double begin, double change, double duration, double overshoot) {
        time /= duration;
        return change * time * time * ((overshoot + 1.0) * time - overshoot) + begin;
    }

    double BLI_easing_back_ease_out(double time, double begin, double change, double duration, double overshoot) {
        time = time / duration - 1.0;
        return change * (time * time * ((overshoot + 1.0) * time + overshoot) + 1.0) + begin;
    }

    double BLI_easing_back_ease_in_out(double time, double begin, double change, double duration, double overshoot) {
        overshoot *= 1.525F;
        if ((time = time / (duration / 2.0)) < 1.0) {
            return change / 2.0 * (time * time * ((overshoot + 1.0) * time - overshoot)) + begin;
        } else {
            time -= 2.0;
            return change / 2.0 * (time * time * ((overshoot + 1.0) * time + overshoot) + 2.0) + begin;
        }
    }

    double BLI_easing_bounce_ease_out(double time, double begin, double change, double duration) {
        time /= duration;
        if (time < 0.36363637F) {
            return change * (7.5625 * time * time) + begin;
        } else if (time < 0.72727275F) {
            time -= 0.54545456F;
            return change * (7.5625 * time * time + 0.75) + begin;
        } else if (time < 0.90909094F) {
            time -= 0.8181818F;
            return change * (7.5625 * time * time + 0.9375) + begin;
        } else {
            time -= 0.95454544F;
            return change * (7.5625 * time * time + 0.984375) + begin;
        }
    }

    double BLI_easing_bounce_ease_in(double time, double begin, double change, double duration) {
        return change - this.BLI_easing_bounce_ease_out(duration - time, 0.0, change, duration) + begin;
    }

    double BLI_easing_bounce_ease_in_out(double time, double begin, double change, double duration) {
        return time < duration / 2.0
            ? this.BLI_easing_bounce_ease_in(time * 2.0, 0.0, change, duration) * 0.5 + begin
            : this.BLI_easing_bounce_ease_out(time * 2.0 - duration, 0.0, change, duration) * 0.5 + change * 0.5 + begin;
    }

    double BLI_easing_circ_ease_in(double time, double begin, double change, double duration) {
        time /= duration;
        return -change * (Math.sqrt(1.0 - time * time) - 1.0) + begin;
    }

    double BLI_easing_circ_ease_out(double time, double begin, double change, double duration) {
        time = time / duration - 1.0;
        return change * Math.sqrt(1.0 - time * time) + begin;
    }

    double BLI_easing_circ_ease_in_out(double time, double begin, double change, double duration) {
        if ((time = time / (duration / 2.0)) < 1.0) {
            return -change / 2.0 * (Math.sqrt(1.0 - time * time) - 1.0) + begin;
        } else {
            time -= 2.0;
            return change / 2.0 * (Math.sqrt(1.0 - time * time) + 1.0) + begin;
        }
    }

    double BLI_easing_cubic_ease_in(double time, double begin, double change, double duration) {
        time /= duration;
        return change * time * time * time + begin;
    }

    double BLI_easing_cubic_ease_out(double time, double begin, double change, double duration) {
        time = time / duration - 1.0;
        return change * (time * time * time + 1.0) + begin;
    }

    double BLI_easing_cubic_ease_in_out(double time, double begin, double change, double duration) {
        if ((time = time / (duration / 2.0)) < 1.0) {
            return change / 2.0 * time * time * time + begin;
        } else {
            time -= 2.0;
            return change / 2.0 * (time * time * time + 2.0) + begin;
        }
    }

    double elastic_blend(double time, double change, double duration, double amplitude, double s, double f) {
        if (change != 0.0) {
            double t = Math.abs(s);
            if (amplitude != 0.0) {
                f *= amplitude / Math.abs(change);
            } else {
                f = 0.0;
            }

            if (Math.abs(time * duration) < t) {
                double l = Math.abs(time * duration) / t;
                f = f * l + (1.0 - l);
            }
        }

        return f;
    }

    double BLI_easing_elastic_ease_in(double time, double begin, double change, double duration, double amplitude, double period) {
        double f = 1.0;
        if (time == 0.0) {
            return begin;
        } else if ((time = time / duration) == 1.0) {
            return begin + change;
        } else {
            time--;
            if (period == 0.0) {
                period = duration * 0.3F;
            }

            double s;
            if (amplitude != 0.0 && !(amplitude < Math.abs(change))) {
                s = period / (Math.PI * 2) * Math.asin(change / amplitude);
            } else {
                s = period / 4.0;
                f = this.elastic_blend(time, change, duration, amplitude, s, f);
                amplitude = change;
            }

            return -f * (amplitude * Math.pow(2.0, 10.0 * time) * Math.sin((time * duration - s) * (Math.PI * 2) / period)) + begin;
        }
    }

    double BLI_easing_elastic_ease_out(double time, double begin, double change, double duration, double amplitude, double period) {
        double f = 1.0;
        if (time == 0.0) {
            return begin;
        } else if ((time = time / duration) == 1.0) {
            return begin + change;
        } else {
            time = -time;
            if (period == 0.0) {
                period = duration * 0.3F;
            }

            double s;
            if (amplitude != 0.0 && !(amplitude < Math.abs(change))) {
                s = period / (Math.PI * 2) * Math.asin(change / amplitude);
            } else {
                s = period / 4.0;
                f = this.elastic_blend(time, change, duration, amplitude, s, f);
                amplitude = change;
            }

            return f * (amplitude * Math.pow(2.0, 10.0 * time) * Math.sin((time * duration - s) * (Math.PI * 2) / period)) + change + begin;
        }
    }

    double BLI_easing_elastic_ease_in_out(double time, double begin, double change, double duration, double amplitude, double period) {
        double f = 1.0;
        if (time == 0.0) {
            return begin;
        } else if ((time = time / (duration / 2.0)) == 2.0) {
            return begin + change;
        } else {
            time--;
            if (period == 0.0) {
                period = duration * 0.45000002F;
            }

            double s;
            if (amplitude != 0.0 && !(amplitude < Math.abs(change))) {
                s = period / (Math.PI * 2) * Math.asin(change / amplitude);
            } else {
                s = period / 4.0;
                f = this.elastic_blend(time, change, duration, amplitude, s, f);
                amplitude = change;
            }

            if (time < 0.0) {
                f *= -0.5;
                return f * (amplitude * Math.pow(2.0, 10.0 * time) * Math.sin((time * duration - s) * (Math.PI * 2) / period)) + begin;
            } else {
                time = -time;
                f *= 0.5;
                return f * (amplitude * Math.pow(2.0, 10.0 * time) * Math.sin((time * duration - s) * (Math.PI * 2) / period)) + change + begin;
            }
        }
    }

    double BLI_easing_expo_ease_in(double time, double begin, double change, double duration) {
        return time == 0.0 ? begin : change * (Math.pow(2.0, 10.0 * (time / duration - 1.0)) - 9.765625E-4) * 1.0009775F + begin;
    }

    double BLI_easing_expo_ease_out(double time, double begin, double change, double duration) {
        return time == 0.0 ? begin : change * (1.0 - (Math.pow(2.0, -10.0 * time / duration) - 9.765625E-4) * 1.0009775F) + begin;
    }

    double BLI_easing_expo_ease_in_out(double time, double begin, double change, double duration) {
        double duration_half = duration / 2.0;
        double change_half = change / 2.0;
        return time <= duration_half
            ? this.BLI_easing_expo_ease_in(time, begin, change_half, duration_half)
            : this.BLI_easing_expo_ease_out(time - duration_half, begin + change_half, change_half, duration_half);
    }

    double BLI_easing_linear_ease(double time, double begin, double change, double duration) {
        return change * time / duration + begin;
    }

    double BLI_easing_quad_ease_in(double time, double begin, double change, double duration) {
        time /= duration;
        return change * time * time + begin;
    }

    double BLI_easing_quad_ease_out(double time, double begin, double change, double duration) {
        time /= duration;
        return -change * time * (time - 2.0) + begin;
    }

    double BLI_easing_quad_ease_in_out(double time, double begin, double change, double duration) {
        if ((time = time / (duration / 2.0)) < 1.0) {
            return change / 2.0 * time * time + begin;
        } else {
            time--;
            return -change / 2.0 * (time * (time - 2.0) - 1.0) + begin;
        }
    }

    double BLI_easing_quart_ease_in(double time, double begin, double change, double duration) {
        time /= duration;
        return change * time * time * time * time + begin;
    }

    double BLI_easing_quart_ease_out(double time, double begin, double change, double duration) {
        time = time / duration - 1.0;
        return -change * (time * time * time * time - 1.0) + begin;
    }

    double BLI_easing_quart_ease_in_out(double time, double begin, double change, double duration) {
        if ((time = time / (duration / 2.0)) < 1.0) {
            return change / 2.0 * time * time * time * time + begin;
        } else {
            time -= 2.0;
            return -change / 2.0 * (time * time * time * time - 2.0) + begin;
        }
    }

    double BLI_easing_quint_ease_in(double time, double begin, double change, double duration) {
        time /= duration;
        return change * time * time * time * time * time + begin;
    }

    double BLI_easing_quint_ease_out(double time, double begin, double change, double duration) {
        time = time / duration - 1.0;
        return change * (time * time * time * time * time + 1.0) + begin;
    }

    double BLI_easing_quint_ease_in_out(double time, double begin, double change, double duration) {
        if ((time = time / (duration / 2.0)) < 1.0) {
            return change / 2.0 * time * time * time * time * time + begin;
        } else {
            time -= 2.0;
            return change / 2.0 * (time * time * time * time * time + 2.0) + begin;
        }
    }

    double BLI_easing_sine_ease_in(double time, double begin, double change, double duration) {
        return -change * Math.cos(time / duration * Math.PI / 2.0) + change + begin;
    }

    double BLI_easing_sine_ease_out(double time, double begin, double change, double duration) {
        return change * Math.sin(time / duration * Math.PI / 2.0) + begin;
    }

    double BLI_easing_sine_ease_in_out(double time, double begin, double change, double duration) {
        return -change / 2.0 * (Math.cos(Math.PI * time / duration) - 1.0) + begin;
    }

    public static enum EType {
        AUTO,
        EASE_IN,
        EASE_OUT,
        EASE_IN_OUT;
    }

    public static enum HType {
        FREE,
        ALIGNED,
        VECTOR,
        AUTO,
        AUTO_CLAMPED;
    }

    public static enum IType {
        CONSTANT,
        LINEAR,
        SIN_UP,
        SIN_DOWN,
        SIN_FULL,
        BEZIER,
        SINE,
        QUAD,
        CUBIC,
        QUART,
        QUINT,
        EXPO,
        CIRC,
        BOUNCE,
        ELASTIC,
        BACK;
    }
}
