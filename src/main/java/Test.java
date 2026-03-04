import com.weixiao.obj.GitObject;
import com.weixiao.repo.Repository;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;

/**
 * @author changzheng
 * @date 2026年03月03日 14:29
 */
public class Test {
    public static void main(String[] args) throws IOException {
        Repository.find(Path.of("/Users/changzheng.15/IdeaProjects/learn-project/java-git"));
        GitObject load = Repository.INSTANCE.getDatabase().load("5a0766dfe1ac80de9229a912ee4d23190817f35b");
        System.out.println(load);
    }
}
