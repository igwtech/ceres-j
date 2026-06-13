// 0054414c  switchD_00543aef::caseD_17

/* WARNING: Function: __security_check_cookie replaced with injection: security_check_cookie */

uint switchD_00543aef::caseD_17(void)

{
  FILE *pFVar1;
  undefined4 uVar2;
  uint in_EAX;
  long lVar3;
  int iVar4;
  int unaff_EBP;
  undefined4 *unaff_EDI;
  
  if ((unaff_EDI != (undefined4 *)0x0) && (*(int *)(unaff_EBP + 0xc) == 0xc)) {
    pFVar1 = (FILE *)*unaff_EDI;
    *(undefined4 *)(unaff_EBP + -0xa8) = 0xc;
    fwrite((void *)(unaff_EBP + -0xa8),1,4,pFVar1);
    uVar2 = unaff_EDI[1];
    pFVar1 = (FILE *)*unaff_EDI;
    *(undefined4 *)(unaff_EBP + -0x84) = 0xffcf;
    *(undefined4 *)(unaff_EBP + -0x80) = uVar2;
    *(undefined4 *)(unaff_EBP + -0x7c) = 0;
    lVar3 = ftell(pFVar1);
    pFVar1 = (FILE *)*unaff_EDI;
    *(long *)(unaff_EBP + -0xb4) = lVar3;
    in_EAX = fwrite((void *)(unaff_EBP + -0x84),1,0xc,pFVar1);
    if (unaff_EDI[1] == 1) {
      uVar2 = *unaff_EDI;
      *(undefined4 *)(unaff_EBP + -0x88) = 0;
      *(undefined4 *)(unaff_EBP + -0x8c) = 1;
      *(undefined4 *)(unaff_EBP + -0x90) = uVar2;
      iVar4 = thunk_FUN_004e1f70();
      *(int *)(unaff_EBP + -0x9c) = iVar4;
      while (iVar4 != 0) {
        if (*(int **)(iVar4 + 8) != (int *)0x0) {
          (**(code **)(**(int **)(iVar4 + 8) + 0x28))(0x17,0xc,unaff_EBP + -0x90);
          iVar4 = *(int *)(unaff_EBP + -0x9c);
        }
        iVar4 = thunk_FUN_004e1f90(iVar4);
        *(int *)(unaff_EBP + -0x9c) = iVar4;
      }
      lVar3 = ftell((FILE *)*unaff_EDI);
      *(long *)(unaff_EBP + -0x7c) = (lVar3 - *(int *)(unaff_EBP + -0xb4)) + -0xc;
      fseek((FILE *)**(undefined4 **)(unaff_EBP + -0xa0),*(int *)(unaff_EBP + -0xb4),0);
      fwrite((void *)(unaff_EBP + -0x84),1,0xc,(FILE *)**(undefined4 **)(unaff_EBP + -0xa0));
      in_EAX = fseek((FILE *)**(undefined4 **)(unaff_EBP + -0xa0),lVar3,0);
    }
  }
  ExceptionList = *(void **)(unaff_EBP + -0xc);
  return in_EAX & 0xffffff00;
}



