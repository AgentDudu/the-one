package gui.playfield;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NodeList;

import core.Connection;
import core.Coord;
import core.DTNHost;
import core.NetworkInterface;
import core.SimClock;

public class NodeGraphic extends PlayFieldGraphic {
  private static boolean drawCoverage;
  private static boolean drawNodeName;
  private static boolean drawConnections;
  private static boolean drawBuffer;
  private static List<DTNHost> highlightedNodes;

  private static Color rangeColor = Color.GREEN;
  private static Color conColor = Color.BLACK;
  private static Color hostNameColor = Color.BLUE;
  private static Color msgColor1 = Color.BLUE;
  private static Color msgColor2 = Color.GREEN;
  private static Color msgColor3 = Color.RED;
  private static Color highlightedNodeColor = Color.MAGENTA;

  private static boolean trailDrawingEnabledConfig = false;
  private static int trailMaxLengthConfig = 50;
  private static Color defaultTrailColorConfig = Color.GRAY;
  private static boolean trailFadeEnabledConfig = true;
  private static boolean trailSettingsConfigured = false;
  private LinkedList<Coord> positionHistory;
  private Color myTrailColor;

  private static class GifFrame {
    BufferedImage image;
    int delay;

    GifFrame(BufferedImage image, int delay) {
      this.image = image;
      this.delay = delay;
    }
  }

  private static List<GifFrame> oiiaCatFrames;
  private static final String OIIA_CAT_GIF_PATH = "/gui/playfield/oiia_cat.gif";
  private static final int NODE_IMAGE_SIZE_SIM_UNITS = 30;
  private static final double GIF_PLAYBACK_SPEED_MULTIPLIER = 2500.0;

  private static int instanceCounter = 0;
  private int myInstanceId;

  private DTNHost node;
  private int currentFrameIndex = 0;
  private double lastFrameUpdateTime = 0;

  static {
    try {
      oiiaCatFrames = loadGifFrames(OIIA_CAT_GIF_PATH);
      // if (oiiaCatFrames == null || oiiaCatFrames.isEmpty()) {
      // System.err.println("Could not load Oiia cat GIF frames from: " +
      // OIIA_CAT_GIF_PATH);
      // } else {
      // System.out.println("Successfully loaded " + oiiaCatFrames.size() + " GIF
      // frames.");
      // }
    } catch (IOException e) {
      System.err.println("Error loading Oiia cat GIF: " + e.getMessage());
      e.printStackTrace();
      oiiaCatFrames = null;
    }
  }

  private static List<GifFrame> loadGifFrames(String resourcePath) throws IOException {
    List<GifFrame> frames = new ArrayList<>();
    ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
    InputStream is = NodeGraphic.class.getResourceAsStream(resourcePath);
    if (is == null) {
      throw new IOException("Resource not found: " + resourcePath);
    }
    ImageInputStream iis = ImageIO.createImageInputStream(is);
    reader.setInput(iis, false);

    int numFrames = reader.getNumImages(true);
    for (int i = 0; i < numFrames; i++) {
      BufferedImage image = reader.read(i);
      IIOMetadata metadata = reader.getImageMetadata(i);
      String metaFormatName = metadata.getNativeMetadataFormatName();
      IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metaFormatName);

      IIOMetadataNode graphicsControlExtensionNode = findNode(root, "GraphicControlExtension");
      int delay = 100;
      if (graphicsControlExtensionNode != null) {
        String delayStr = graphicsControlExtensionNode.getAttribute("delayTime");
        if (delayStr != null && !delayStr.isEmpty()) {
          try {
            delay = Integer.parseInt(delayStr) * 10;
          } catch (NumberFormatException e) {
            System.err
                .println("Warning: Could not parse delayTime '" + delayStr + "' for frame " + i + ". Using default.");
          }
        }
      }
      if (delay < 20) {
        // System.err.println("Adjusting very small/zero GIF delay for frame " + i + "
        // from " + delay + "ms to 50ms");
        delay = 50;
      }
      frames.add(new GifFrame(image, delay));
    }
    iis.close();
    is.close();
    return frames;
  }

  private static IIOMetadataNode findNode(IIOMetadataNode rootNode, String nodeName) {
    if (rootNode == null)
      return null;
    if (rootNode.getNodeName().equalsIgnoreCase(nodeName)) {
      return rootNode;
    }
    NodeList childNodes = rootNode.getChildNodes();
    for (int i = 0; i < childNodes.getLength(); i++) {
      org.w3c.dom.Node child = childNodes.item(i);
      if (child instanceof IIOMetadataNode) {
        IIOMetadataNode foundNode = findNode((IIOMetadataNode) child, nodeName);
        if (foundNode != null) {
          return foundNode;
        }
      }
    }
    return null;
  }

  public NodeGraphic(DTNHost node) {
    this.node = node;
    this.myInstanceId = instanceCounter++;

    this.lastFrameUpdateTime = SimClock.getTime();
    if (oiiaCatFrames != null && !oiiaCatFrames.isEmpty()) {
      this.currentFrameIndex = (int) (Math.random() * oiiaCatFrames.size());
    }

    if (trailDrawingEnabledConfig) {
      this.positionHistory = new LinkedList<>();
      int hostAddr = node.getAddress();
      if (hostAddr % 3 == 0) {
        this.myTrailColor = new Color(50, 50, 200); // Bluish
      } else if (hostAddr % 3 == 1) {
        this.myTrailColor = new Color(50, 200, 50); // Greenish
      } else {
        this.myTrailColor = new Color(200, 50, 50); // Reddish
      }
      // Or to use the default color read from settings:
      // this.myTrailColor = defaultTrailColorConfig;
    } else {
      this.positionHistory = null;
      this.myTrailColor = null;
    }
  }

  @Override
  public void draw(Graphics2D g2) {
    drawHost(g2);
    if (drawBuffer) {
      drawMessages(g2);
    }
  }

  private boolean isHighlighted() {
    if (highlightedNodes == null)
      return false;
    return highlightedNodes.contains(node);
  }

  private void drawHost(Graphics2D g2) {
    Coord loc = node.getLocation();
    double simTime = SimClock.getTime();

    if (trailDrawingEnabledConfig && trailSettingsConfigured) {
      updateAndDrawTrail(g2);
    }

    if (oiiaCatFrames != null && !oiiaCatFrames.isEmpty()) {
      GifFrame currentFrameData = oiiaCatFrames.get(currentFrameIndex);
      double actualGifFrameDelayMs = currentFrameData.delay;
      double targetDisplayDurationSimSeconds = (actualGifFrameDelayMs / 1000.0) * GIF_PLAYBACK_SPEED_MULTIPLIER;
      double simTimeElapsedSincePriorFrameUpdate = simTime - this.lastFrameUpdateTime;

      if (simTimeElapsedSincePriorFrameUpdate >= targetDisplayDurationSimSeconds
          && targetDisplayDurationSimSeconds > 0) {
        int framesToAdvance = (int) Math.floor(simTimeElapsedSincePriorFrameUpdate / targetDisplayDurationSimSeconds);
        framesToAdvance = Math.max(1, framesToAdvance);
        currentFrameIndex = (currentFrameIndex + framesToAdvance) % oiiaCatFrames.size();
        this.lastFrameUpdateTime += (framesToAdvance * targetDisplayDurationSimSeconds);

        double fullAnimationCycleDurationSimSeconds = 0;
        for (GifFrame frame : oiiaCatFrames) {
          fullAnimationCycleDurationSimSeconds += (frame.delay / 1000.0) * GIF_PLAYBACK_SPEED_MULTIPLIER;
        }
        double maxLagBeforeReset = Math.max(targetDisplayDurationSimSeconds * 5,
            fullAnimationCycleDurationSimSeconds * 0.5);
        if (fullAnimationCycleDurationSimSeconds <= 0)
          maxLagBeforeReset = targetDisplayDurationSimSeconds * 5;
        if (simTime - this.lastFrameUpdateTime > maxLagBeforeReset && maxLagBeforeReset > 0) {
          this.lastFrameUpdateTime = simTime;
        }
      }
    }
    if (oiiaCatFrames != null && !oiiaCatFrames.isEmpty()) {
      BufferedImage currentImageToDraw = oiiaCatFrames.get(currentFrameIndex).image;
      if (currentImageToDraw != null) {
        int scaledImgSize = scale(NODE_IMAGE_SIZE_SIM_UNITS);
        if (scaledImgSize > 0) {
          int imgCenterX = scale(loc.getX());
          int imgCenterY = scale(loc.getY());
          g2.drawImage(currentImageToDraw,
              imgCenterX - scaledImgSize / 2,
              imgCenterY - scaledImgSize / 2,
              scaledImgSize, scaledImgSize, null);
        }
      }
    } else {
      g2.setColor(Color.RED);
      g2.drawRect(scale(loc.getX() - 1), scale(loc.getY() - 1), scale(2), scale(2));
      g2.drawString("GFX_ERR", scale(loc.getX()), scale(loc.getY()));
    }
    if (drawCoverage && node.isRadioActive()) {
      ArrayList<NetworkInterface> interfaces = new ArrayList<>(node.getInterfaces());
      for (NetworkInterface ni : interfaces) {
        double range = ni.getTransmitRange();
        Ellipse2D.Double coverage = new Ellipse2D.Double(scale(loc.getX() - range),
            scale(loc.getY() - range), scale(range * 2), scale(range * 2));
        g2.setColor(rangeColor);
        g2.draw(coverage);
      }
    }
    if (drawConnections) {
      g2.setColor(conColor);
      Coord c1 = node.getLocation();
      ArrayList<Connection> conList = new ArrayList<>(node.getConnections());
      for (Connection c : conList) {
        DTNHost otherNode = c.getOtherNode(node);
        if (otherNode == null)
          continue;
        Coord c2 = otherNode.getLocation();
        g2.drawLine(scale(c1.getX()), scale(c1.getY()), scale(c2.getX()), scale(c2.getY()));
      }
    }
    if (isHighlighted()) {
      g2.setColor(highlightedNodeColor);
      int highlightSize = scale(NODE_IMAGE_SIZE_SIM_UNITS + 2);
      g2.drawRect(scale(loc.getX()) - highlightSize / 2,
          scale(loc.getY()) - highlightSize / 2,
          highlightSize, highlightSize);
    }
    if (drawNodeName) {
      g2.setColor(hostNameColor);
      g2.drawString(node.toString(), scale(loc.getX()) + scale(NODE_IMAGE_SIZE_SIM_UNITS) / 2 + 2,
          scale(loc.getY()));
    }
  }

  public static void configureTrails(boolean enabled, int maxLength, Color defaultColor, boolean fadeEnabled) {
    trailDrawingEnabledConfig = enabled;
    trailMaxLengthConfig = maxLength;
    defaultTrailColorConfig = defaultColor;
    trailFadeEnabledConfig = fadeEnabled;
    trailSettingsConfigured = true;
    System.out.println(String.format(
        "NodeGraphic Trails Configured: Enabled=%b, Length=%d, Color=%s, Fade=%b",
        enabled, maxLength, defaultColor, fadeEnabled));
  }

  private void updateAndDrawTrail(Graphics2D g2) {
    if (this.positionHistory == null) {
      return;
    }

    Coord currentLocation = node.getLocation();

    if (positionHistory.isEmpty() || !positionHistory.getLast().equals(currentLocation)) {
      positionHistory.addLast(currentLocation.clone());
    }

    if (trailFadeEnabledConfig) {
      while (positionHistory.size() > trailMaxLengthConfig) {
        positionHistory.removeFirst();
      }
    }

    if (positionHistory.size() < 2) {
      return;
    }

    Coord prevPoint = null;
    int historySize = positionHistory.size();
    int pointIndex = 0;

    for (Coord currentPoint : positionHistory) {
      if (prevPoint != null) {
        Color segmentColor;

        if (trailFadeEnabledConfig) {
          float alpha = (float) pointIndex / (float) (Math.max(1, historySize - 1));
          alpha = Math.max(0.1f, alpha);
          alpha = Math.min(1.0f, alpha);

          segmentColor = new Color(
              this.myTrailColor.getRed() / 255f,
              this.myTrailColor.getGreen() / 255f,
              this.myTrailColor.getBlue() / 255f,
              alpha);
        } else {
          segmentColor = this.myTrailColor;
        }

        g2.setColor(segmentColor);
        g2.drawLine(scale(prevPoint.getX()), scale(prevPoint.getY()),
            scale(currentPoint.getX()), scale(currentPoint.getY()));
      }
      prevPoint = currentPoint;
      pointIndex++;
    }
  }

  public static void setDrawCoverage(boolean draw) {
    drawCoverage = draw;
  }

  public static void setDrawNodeName(boolean draw) {
    drawNodeName = draw;
  }

  public static void setDrawConnections(boolean draw) {
    drawConnections = draw;
  }

  public static void setDrawBuffer(boolean draw) {
    drawBuffer = draw;
  }

  public static void setHighlightedNodes(List<DTNHost> nodes) {
    highlightedNodes = nodes;
  }

  private void drawMessages(Graphics2D g2) {
    int nrofMessages = node.getNrofMessages();
    Coord loc = node.getLocation();
    double catImageScaledRadius = scale(NODE_IMAGE_SIZE_SIM_UNITS) / 2.0;
    drawBar(g2, loc, nrofMessages % 10, 1, catImageScaledRadius);
    drawBar(g2, loc, nrofMessages / 10, 2, catImageScaledRadius);
  }

  private void drawBar(Graphics2D g2, Coord loc, int nrof, int col, double offset) {
    final int BAR_HEIGHT_SIM = 1;
    final int BAR_WIDTH_SIM = 1;
    final int BAR_DISPLACEMENT_X_SIM = 2 + NODE_IMAGE_SIZE_SIM_UNITS / 2;
    int scaledBarHeight = Math.max(1, scale(BAR_HEIGHT_SIM));
    int scaledBarWidth = Math.max(1, scale(BAR_WIDTH_SIM));

    for (int i = 1; i <= nrof; i++) {
      if (i % 2 == 0)
        g2.setColor(msgColor1);
      else
        g2.setColor((col > 1) ? msgColor3 : msgColor2);
      g2.fillRect(scale(loc.getX()) + (int) offset + scale(BAR_DISPLACEMENT_X_SIM) + (scaledBarWidth * (col - 1)),
          scale(loc.getY()) - scale(NODE_IMAGE_SIZE_SIM_UNITS / 2.0) - (i * scaledBarHeight),
          scaledBarWidth, scaledBarHeight);
    }
  }
}
