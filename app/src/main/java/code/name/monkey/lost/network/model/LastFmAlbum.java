package code.name.monkey.lost.network.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class LastFmAlbum {

  @Expose private Album album;

  public Album getAlbum() {
    return album;
  }

  public void setAlbum(Album album) {
    this.album = album;
  }

  public static class Album {

    @Expose public String listeners;
    @Expose public String playcount;
    @Expose private List<Image> image = new ArrayList<>();
    @Expose private String name;
    @Expose private Tags tags;
    @Expose private Wiki wiki;

      public Album(Tags tags) {
          this.tags = tags;
      }

      public List<Image> getImage() {
      return image;
    }

    public void setImage(List<Image> image) {
      this.image = image;
    }

    public String getListeners() {
      return listeners;
    }

    public void setListeners(final String listeners) {
      this.listeners = listeners;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public Tags getTags() {
      return tags;
    }

    public Wiki getWiki() {
      return wiki;
    }

    public static class Image {

      @SerializedName("#text")
      @Expose
      private String Text;

      @Expose private String size;

      public String getSize() {
        return size;
      }

      public void setSize(String size) {
        this.size = size;
      }

      public String getText() {
        return Text;
      }

      public void setText(String Text) {
        this.Text = Text;
      }
    }

    public static class Tags {

        public List<Tag> getTag() {
        return null;
      }
    }

    public static class Tag {

      @Expose private String name;

      @Expose private String url;

        public Tag(String name) {
            this.name = name;
        }

        public String getName() {
        return name;
      }

      public String getUrl() {
        return url;
      }
    }

    public static class Wiki {

      @Expose private String content;

      @Expose private String published;

      public String getContent() {
        return content;
      }

      public void setContent(String content) {
        this.content = content;
      }

      public String getPublished() {
        return published;
      }

      public void setPublished(final String published) {
        this.published = published;
      }
    }
  }
}
