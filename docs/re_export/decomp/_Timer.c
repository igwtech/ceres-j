// 00402ae5  Concurrency::details::_Timer::~_Timer

void __thiscall Concurrency::details::_Timer::~_Timer(_Timer *this)

{
  uint uVar1;
  void *pvStack_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009c0110;
  pvStack_10 = ExceptionList;
  uVar1 = DAT_00b13e00 ^ (uint)&stack0xfffffffc;
  ExceptionList = &pvStack_10;
  *(undefined ***)this =
       std::
       _Fmt_iterator_buffer<class_std::back_insert_iterator<class_std::basic_string<char,struct_std::char_traits<char>,class_std::allocator<char>_>_>,char,struct_std::_Fmt_buffer_traits>
       ::vftable;
  if (*(int *)(this + 8) != 0) {
    thunk_FUN_0049e220(uVar1);
  }
  ExceptionList = pvStack_10;
  return;
}



// 0049a5b0  Concurrency::details::_Timer::~_Timer

/* Library Function - Single Match
    protected: virtual __thiscall Concurrency::details::_Timer::~_Timer(void)
   
   Library: Visual Studio 2015 Release */

void __thiscall Concurrency::details::_Timer::~_Timer(_Timer *this)

{
  uint uVar1;
  void *local_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009c0110;
  local_10 = ExceptionList;
  uVar1 = DAT_00b13e00 ^ (uint)&stack0xfffffffc;
  ExceptionList = &local_10;
  *(undefined ***)this =
       std::
       _Fmt_iterator_buffer<class_std::back_insert_iterator<class_std::basic_string<char,struct_std::char_traits<char>,class_std::allocator<char>_>_>,char,struct_std::_Fmt_buffer_traits>
       ::vftable;
  if (*(int *)(this + 8) != 0) {
    thunk_FUN_0049e220(uVar1);
  }
  ExceptionList = local_10;
  return;
}



