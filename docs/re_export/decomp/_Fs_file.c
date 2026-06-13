// 009aa4ec  std::_Fs_file::_Fs_file

/* Library Function - Single Match
    public: __thiscall std::_Fs_file::_Fs_file(wchar_t const * const,enum __std_access_rights,enum
   __std_fs_file_flags,enum __std_win_error * const)
   
   Libraries: Visual Studio 2017 Release, Visual Studio 2019 Release */

_Fs_file * __thiscall
std::_Fs_file::_Fs_file
          (_Fs_file *this,wchar_t *param_1,__std_access_rights param_2,__std_fs_file_flags param_3,
          __std_win_error *param_4)

{
  __std_win_error _Var1;
  
  _Var1 = ___std_fs_open_handle_16(this,param_1,param_2,param_3);
  *param_4 = _Var1;
  return this;
}



