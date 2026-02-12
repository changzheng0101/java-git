# 代码贡献指南
## 调试常用命令
查看blob的具体内容
git cat-file -p hash256

查看当前HEAD对应的tree
git cat-file -p HEAD^{tree}

查看暂存区中的内容
git ls-files --stage