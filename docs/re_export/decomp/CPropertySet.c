// 0040a92a  CPropertySet::~CPropertySet

void __thiscall CPropertySet::~CPropertySet(CPropertySet *this)

{
  void *pvStack_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009df9b0;
  pvStack_10 = ExceptionList;
  ExceptionList = &pvStack_10;
  thunk_FUN_0074e030(DAT_00b13e00 ^ (uint)&stack0xfffffffc);
  thunk_FUN_0074d080();
  ExceptionList = pvStack_10;
  return;
}



// 0074d020  CPropertySet::~CPropertySet

/* Library Function - Single Match
    public: __thiscall CPropertySet::~CPropertySet(void)
   
   Library: Visual Studio 2015 Release */

void __thiscall CPropertySet::~CPropertySet(CPropertySet *this)

{
  void *local_10;
  undefined1 *puStack_c;
  undefined4 uStack_8;
  
  uStack_8 = 0xffffffff;
  puStack_c = &LAB_009df9b0;
  local_10 = ExceptionList;
  ExceptionList = &local_10;
  thunk_FUN_0074e030(DAT_00b13e00 ^ (uint)&stack0xfffffffc);
  thunk_FUN_0074d080();
  ExceptionList = local_10;
  return;
}



