%dw 2.0
import * from dw::io::file::FileSystem
output application/json
var folder = path(tmp(),"dw_io_test")
---
{
  a: (ls(folder) orderBy $) map ((path, index) -> kindOf(path)),
  b: kindOf(folder)
}