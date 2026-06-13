// 004164e6  std::basic_stringstream<char,struct_std::char_traits<char>,class_std::allocator<char>_>::`scalar_deleting_destructor'

void * __thiscall
std::basic_stringstream<char,struct_std::char_traits<char>,class_std::allocator<char>_>::
_scalar_deleting_destructor_
          (basic_stringstream<char,struct_std::char_traits<char>,class_std::allocator<char>_> *this,
          uint param_1)

{
  void *pvVar1;
  
  pvVar1 = _scalar_deleting_destructor_(this,param_1);
  return pvVar1;
}



// 00447140  std::basic_stringstream<char,struct_std::char_traits<char>,class_std::allocator<char>_>::`scalar_deleting_destructor'

/* Library Function - Single Match
    public: virtual void * __thiscall std::basic_stringstream<char,struct
   std::char_traits<char>,class std::allocator<char> >::`scalar deleting destructor'(unsigned int)
   
   Library: Visual Studio 2019 Release */

void * __thiscall
std::basic_stringstream<char,struct_std::char_traits<char>,class_std::allocator<char>_>::
_scalar_deleting_destructor_
          (basic_stringstream<char,struct_std::char_traits<char>,class_std::allocator<char>_> *this,
          uint param_1)

{
  thunk_FUN_00447060();
  if ((param_1 & 1) != 0) {
    thunk_FUN_009ac142(this + -0x68,0xb0);
  }
  return this + -0x68;
}



