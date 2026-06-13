// 00405916  std::error_category::equivalent

bool __thiscall
std::error_category::equivalent(error_category *this,int param_1,error_condition *param_2)

{
  int *piVar1;
  undefined1 auStack_c [8];
  
  piVar1 = (int *)(**(code **)(*(int *)this + 0xc))(auStack_c,param_1);
  if ((*(int *)(piVar1[1] + 4) == *(int *)(*(int *)(param_2 + 4) + 4)) &&
     (*piVar1 == *(int *)param_2)) {
    return true;
  }
  return false;
}



// 0040f957  std::error_category::equivalent

bool __thiscall
std::error_category::equivalent(error_category *this,error_code *param_1,int param_2)

{
  if ((*(int *)(this + 4) == *(int *)(*(int *)(param_1 + 4) + 4)) && (*(int *)param_1 == param_2)) {
    return true;
  }
  return false;
}



// 00442e00  std::error_category::equivalent

/* Library Function - Single Match
    public: virtual bool __thiscall std::error_category::equivalent(class std::error_code const
   &,int)const 
   
   Library: Visual Studio 2019 Release */

bool __thiscall
std::error_category::equivalent(error_category *this,error_code *param_1,int param_2)

{
  if ((*(int *)(this + 4) == *(int *)(*(int *)(param_1 + 4) + 4)) && (*(int *)param_1 == param_2)) {
    return true;
  }
  return false;
}



// 00442e30  std::error_category::equivalent

/* Library Function - Single Match
    public: virtual bool __thiscall std::error_category::equivalent(int,class std::error_condition
   const &)const 
   
   Library: Visual Studio 2019 Release */

bool __thiscall
std::error_category::equivalent(error_category *this,int param_1,error_condition *param_2)

{
  int *piVar1;
  undefined1 local_c [8];
  
  piVar1 = (int *)(**(code **)(*(int *)this + 0xc))(local_c,param_1);
  if ((*(int *)(piVar1[1] + 4) == *(int *)(*(int *)(param_2 + 4) + 4)) &&
     (*piVar1 == *(int *)param_2)) {
    return true;
  }
  return false;
}



