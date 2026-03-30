import com.google.common.collect.Iterables;


/**
 * @author changzheng
 * &#064;date  2026年03月03日 14:29
 */
@SuppressWarnings("DataFlowIssue")
public class Test {
    public static void main(String[] args) {
        Integer a = null;
        if (a == 1) {
            System.out.println("a");
        }else {
            System.out.println("b");
        }
        System.out.println(Iterables.isEmpty(null));
    }
}
