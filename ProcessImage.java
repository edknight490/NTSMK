import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class ProcessImage {
    public static void main(String[] args) {
        try {
            File inFile = new File("poolside_orig.png");
            if (!inFile.exists()) {
                System.out.println("Original file not found!");
                return;
            }
            BufferedImage inImg = ImageIO.read(inFile);
            int width = inImg.getWidth();
            int height = inImg.getHeight();
            
            BufferedImage outImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = inImg.getRGB(x, y);
                    int a = (argb >> 24) & 0xff;
                    
                    // Convert the color to black (0, 0, 0) while keeping the original alpha channel
                    int newArgb = (a << 24) | 0x000000;
                    outImg.setRGB(x, y, newArgb);
                }
            }
            
            File outDir = new File("app/src/main/res/drawable");
            if (!outDir.exists()) {
                outDir.mkdirs();
            }
            File outFile = new File(outDir, "ic_poolside.png");
            ImageIO.write(outImg, "png", outFile);
            System.out.println("Successfully processed and saved image to: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
