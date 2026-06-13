// 0040d85f  Concurrency::call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>::~call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>

void __thiscall
Concurrency::call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>::
~call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>
          (call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_> *this)

{
  uint uVar1;
  void *pvStack_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009e0f30;
  pvStack_10 = ExceptionList;
  uVar1 = DAT_00b13e00 ^ (uint)&stack0xfffffffc;
  ExceptionList = &pvStack_10;
  *(undefined ***)this = PSIWEAPON::vftable;
  thunk_FUN_00776b30(uVar1);
  thunk_FUN_00445560();
  thunk_FUN_0076b020();
  ExceptionList = pvStack_10;
  return;
}



// 00776060  Concurrency::call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>::~call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>

/* Library Function - Single Match
    public: virtual __thiscall Concurrency::call<unsigned int,class std::function<void
   __cdecl(unsigned int const &)> >::~call<unsigned int,class std::function<void __cdecl(unsigned
   int const &)> >(void)
   
   Library: Visual Studio 2015 Release */

void __thiscall
Concurrency::call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>::
~call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_>
          (call<unsigned_int,class_std::function<void___cdecl(unsigned_int_const&)>_> *this)

{
  uint uVar1;
  void *local_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009e0f30;
  local_10 = ExceptionList;
  uVar1 = DAT_00b13e00 ^ (uint)&stack0xfffffffc;
  ExceptionList = &local_10;
  *(undefined ***)this = PSIWEAPON::vftable;
  thunk_FUN_00776b30(uVar1);
  thunk_FUN_00445560();
  thunk_FUN_0076b020();
  ExceptionList = local_10;
  return;
}



