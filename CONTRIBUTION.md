# 代码贡献指南
## 调试常用命令
查看blob的具体内容
git cat-file -p hash256

查看当前HEAD对应的tree
git cat-file -p HEAD^{tree}

查看暂存区中的内容
git ls-files --stage

查看文件状态  
git status --porcelain  
?? xxx  
其中??代表这个文件为untracked file


### BCA
找出所有潜在BCA

git merge-base --all branch_A branch_B

找出相应BCA

git merge-base branch_A branch_B

Merge需要构造virtual base的特殊情况
      (Root)
        |
       [C2]  <-- 初始共同起点
       /  \
     [A1] [B1] <-- 两个分支最初的状态
     /  \ /  \
    /    X    \    (这里的 X 就是交叉点)
   /    / \    \
 [A2]  /   \  [B2]
   \  /     \  /
   [M1]     [M2]  <-- 此时我们要 merge(M1, M2)