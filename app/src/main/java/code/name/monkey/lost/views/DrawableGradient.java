package code.name.monkey.lost.views;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;

public class DrawableGradient extends GradientDrawable {
  public DrawableGradient(Orientation orientations, int[] colors, int shape) {
    super();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      setOrientation(orientations);
      setColors(colors);
    }
    try {
      setShape(shape);
      setGradientType(GradientDrawable.LINEAR_GRADIENT);
      setCornerRadius(0);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
