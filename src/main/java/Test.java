import java.nio.file.FileSystems;

/**
 * @author changzheng
 * @date 2026年03月03日 14:29
 */
public class Test {
    public static void main(String[] args) {
        String[] split = "/".trim().split(FileSystems.getDefault().getSeparator());
        System.out.println(split.length);
    }
}
