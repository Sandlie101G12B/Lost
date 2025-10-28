package code.name.monkey.lost.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import code.name.monkey.appthemehelper.util.ColorUtil;

public class LostColorUtil {
  public static int desaturateColor(int color, float ratio) {
    float[] hsv = new float[3];
    Color.colorToHSV(color, hsv);

    hsv[1] = (hsv[1] * ratio) + (0.2f * (1.0f - ratio));

    return Color.HSVToColor(hsv);
  }

  @Nullable
  public static Palette generatePalette(@Nullable Bitmap bitmap) {
    return bitmap == null ? null : Palette.from(bitmap).clearFilters().generate();
  }

  public static int getTextColor(@Nullable Palette palette) {
    if (palette == null) {
      return -1;
    }

    int inverse = -1;
    if (palette.getVibrantSwatch() != null) {
      inverse = palette.getVibrantSwatch().getRgb();
    } else if (palette.getLightVibrantSwatch() != null) {
      inverse = palette.getLightVibrantSwatch().getRgb();
    } else if (palette.getDarkVibrantSwatch() != null) {
      inverse = palette.getDarkVibrantSwatch().getRgb();
    }

    int background = getSwatch(palette).getRgb();

    if (inverse != -1) {
      return ColorUtil.INSTANCE.getReadableText(inverse, background, 150);
    }
    return ColorUtil.INSTANCE.stripAlpha(getSwatch(palette).getTitleTextColor());
  }

  @NonNull
  public static Palette.Swatch getSwatch(@Nullable Palette palette) {
    if (palette == null) {
      return new Palette.Swatch(Color.WHITE, 1);
    }
    return getBestPaletteSwatchFrom(palette.getSwatches());
  }

  @ColorInt
  public static int getColor(@Nullable Palette palette, int fallback) {
    if (palette != null) {
      if (palette.getVibrantSwatch() != null) {
        return palette.getVibrantSwatch().getRgb();
      } else if (palette.getDarkVibrantSwatch() != null) {
        return palette.getDarkVibrantSwatch().getRgb();
      } else if (palette.getLightVibrantSwatch() != null) {
        return palette.getLightVibrantSwatch().getRgb();
      } else if (palette.getMutedSwatch() != null) {
        return palette.getMutedSwatch().getRgb();
      } else if (palette.getLightMutedSwatch() != null) {
        return palette.getLightMutedSwatch().getRgb();
      } else if (palette.getDarkMutedSwatch() != null) {
        return palette.getDarkMutedSwatch().getRgb();
      } else if (!palette.getSwatches().isEmpty()) {
        return Collections.max(palette.getSwatches(), SwatchComparator.getInstance()).getRgb();
      }
    }
    return fallback;
  }

  @ColorInt
  public static int getBackgroundColor(@Nullable Palette palette) {
    return getProperBackgroundSwatch(palette).getRgb();
  }

  private static Palette.Swatch getProperBackgroundSwatch(@Nullable Palette palette) {
    if (palette == null) {
      return new Palette.Swatch(Color.BLACK, 1);
    }
    if (palette.getDarkMutedSwatch() != null) {
      return palette.getDarkMutedSwatch();
    } else if (palette.getMutedSwatch() != null) {
      return palette.getMutedSwatch();
    } else if (palette.getLightMutedSwatch() != null) {
      return palette.getLightMutedSwatch();
    } else {
      return new Palette.Swatch(Color.BLACK, 1);
    }
  }

  private static Palette.Swatch getBestPaletteSwatchFrom(List<Palette.Swatch> swatches) {
    if (swatches == null) {
      return null;
    }
    return Collections.max(
        swatches,
        (opt1, opt2) -> {
          int a = opt1 == null ? 0 : opt1.getPopulation();
          int b = opt2 == null ? 0 : opt2.getPopulation();
          return a - b;
        });
  }

  private static class SwatchComparator implements Comparator<Palette.Swatch> {

    private static SwatchComparator sInstance;

    static SwatchComparator getInstance() {
      if (sInstance == null) {
        sInstance = new SwatchComparator();
      }
      return sInstance;
    }

    @Override
    public int compare(Palette.Swatch lhs, Palette.Swatch rhs) {
      return lhs.getPopulation() - rhs.getPopulation();
    }
  }
}
