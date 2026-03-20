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



## 构造测试
### Merge命令
构造一个简单的冲突场景，原始数据是1，之后两个commit分表将内容改为2 和 3，之后用Merge合并
```bash
#!/bin/bash


# 1. 创建并进入测试目录
mkdir jit-conflict-test
cd jit-conflict-test

# 2. 初始化仓库
jit init

# 3. 创建原始数据：内容为 1
echo "1" > data.txt
jit add data.txt
jit commit -m "Initial commit: data is 1"

# 4. 创建一个新分支 'branch-A' 并将内容改为 2
jit branch branch-A
jit checkout branch-A
echo "2" > data.txt
jit add data.txt
jit commit -m "Update data to 2 in branch-A"

# 5. 回到主分支 (假设为 master 或 main，请根据 jit 默认分支名调整)
jit checkout master

# 6. 创建另一个新分支 'branch-B' 并将内容改为 3
jit branch branch-B
jit checkout branch-B
echo "3" > data.txt
jit add data.txt
jit commit -m "Update data to 3 in branch-B"
```