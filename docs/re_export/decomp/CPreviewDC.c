// 00412409  CPreviewDC::~CPreviewDC

void __thiscall CPreviewDC::~CPreviewDC(CPreviewDC *this)

{
  uint uVar1;
  void *pvStack_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009edd30;
  pvStack_10 = ExceptionList;
  uVar1 = DAT_00b13e00 ^ (uint)&stack0xfffffffc;
  ExceptionList = &pvStack_10;
  *(undefined ***)this = ICVSSCROLLBAR::vftable;
  thunk_FUN_006192d0(this + 0x6c);
  thunk_FUN_008273c0(uVar1);
  ExceptionList = pvStack_10;
  return;
}



// 00827440  CPreviewDC::~CPreviewDC

/* Library Function - Single Match
    public: virtual __thiscall CPreviewDC::~CPreviewDC(void)
   
   Library: Visual Studio 2015 Release */

void __thiscall CPreviewDC::~CPreviewDC(CPreviewDC *this)

{
  uint uVar1;
  void *local_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009edd30;
  local_10 = ExceptionList;
  uVar1 = DAT_00b13e00 ^ (uint)&stack0xfffffffc;
  ExceptionList = &local_10;
  *(undefined ***)this = ICVSSCROLLBAR::vftable;
  thunk_FUN_006192d0(this + 0x6c);
  thunk_FUN_008273c0(uVar1);
  ExceptionList = local_10;
  return;
}



